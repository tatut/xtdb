(ns dev
  (:require [clojure.java.io :as io]
            [integrant.core :as i]
            [integrant.repl :as ir]
            [xtdb.compactor :as c]
            [xtdb.datasets.tpch :as tpch]
            [xtdb.node :as xtn]
            [xtdb.pgwire :as pgw]
            [xtdb.table-catalog :as table-cat]
            [xtdb.test-util :as tu]
            [xtdb.trie :as trie]
            [xtdb.trie-catalog :as trie-cat]
            [xtdb.types :as types]
            [xtdb.util :as util]
            [xtdb.vector.reader :as vr])
  (:import [java.nio.file Path]
           java.time.Duration
           [java.util List]
           [org.apache.arrow.memory RootAllocator]
           [org.apache.arrow.vector.ipc ArrowFileReader]
           org.roaringbitmap.buffer.ImmutableRoaringBitmap
           (xtdb.arrow Relation Vector)
           (xtdb.block.proto TableBlock)
           (xtdb.log.proto TrieDetails)
           (xtdb.trie ArrowHashTrie ArrowHashTrie$IidBranch ArrowHashTrie$Leaf ArrowHashTrie$Node)))

#_{:clj-kondo/ignore [:unused-namespace :unused-referred-var]}
(require '[xtdb.logging :refer [set-log-level!]])

(def dev-node-dir
  (io/file "dev/dev-node"))

(def node nil)

(defmethod i/init-key ::xtdb [_ {:keys [node-opts]}]
  (alter-var-root #'node (constantly (xtn/start-node node-opts)))
  node)

(defmethod i/halt-key! ::xtdb [_ node]
  (util/try-close node)
  (alter-var-root #'node (constantly nil)))

(def standalone-config
  {::xtdb {:node-opts {:server {:port 5432
                                :ssl {:keystore (io/file (io/resource "xtdb/pgwire/xtdb.jks"))
                                      :keystore-password "password123"}}
                       :log [:local {:path (io/file dev-node-dir "log")}]
                       :storage [:local {:path (io/file dev-node-dir "objects")}]
                       :healthz {:port 8080}
                       :flight-sql-server {:port 52358}}}})

(comment
  (do
    (halt)
    #_(util/delete-dir (util/->path dev-node-dir))
    (go)))

(def playground-config
  {::playground {:port 5439}})

(defmethod i/init-key ::playground [_ {:keys [port]}]
  (pgw/open-playground {:port port}))

(defmethod i/halt-key! ::playground [_ srv]
  (util/close srv))

(ir/set-prep! (fn [] playground-config))
(ir/set-prep! (fn [] standalone-config))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def go ir/go)

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def halt ir/halt)

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def reset ir/reset)

(comment
  #_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
  (def !submit-tpch
    (future
      (time
       (do
         (time (tpch/submit-docs! node 0.5))
         (time (tu/then-await-tx (tu/latest-submitted-tx-id node) node (Duration/ofHours 1)))
         (tu/finish-block! node)
         (time (c/compact-all! node (Duration/ofMinutes 5)))))))

  (do
    (newline)
    (doseq [!q [#'tpch/tpch-q1-pricing-summary-report
                #'tpch/tpch-q5-local-supplier-volume
                #'tpch/tpch-q9-product-type-profit-measure]]
      (prn !q)
      (time (tu/query-ra @!q node)))))


(defn write-arrow-file [^Path path, ^List data]
  (with-open [al (RootAllocator.)
              ch (util/->file-channel path #{:write :create})
              struct-vec (Vector/fromList al (types/col-type->field "my-struct" [:struct {}]) data)]
    (let [rel (Relation. al ^List (into [] (.getVectors struct-vec)) (.getValueCount struct-vec))]
      (with-open [unloader (.startUnload rel ch)]
        (.writePage unloader)
        (.end unloader)))))

(defn read-arrow-file [^Path path]
  (reify clojure.lang.IReduceInit
    (reduce [_ f init]
      (with-open [al (RootAllocator.)
                  ch (util/->file-channel path)
                  rdr (ArrowFileReader. ch al)]
        (.initialize rdr)
        (loop [v init]
          (cond
            (reduced? v) (unreduced v)
            (.loadNextBatch rdr) (recur (f v (.toMaps (vr/<-root (.getVectorSchemaRoot rdr)))))
            :else v))))))

(comment
  (write-arrow-file (util/->path "/tmp/test.arrow")
                    [{:a 1 :b 2} {:a 3 :b "foo" :c {:a 1 :b 2}}])

  (->> (util/->path "/tmp/test.arrow")
       (read-arrow-file)
       (into [] cat)))

(defn read-meta-file
  "Reads the meta file and returns the rendered trie.

     numbers: leaf page idxs
     vectors: iid branches
     maps: recency branches"
  [^Path path]
  (with-open [al (RootAllocator.)
              ch (util/->file-channel path)
              rdr (ArrowFileReader. ch al)]
    (.initialize rdr)
    (.loadNextBatch rdr)

    (letfn [(render-trie [^ArrowHashTrie$Node node]
              (cond
                (instance? ArrowHashTrie$Leaf node) (.getDataPageIndex ^ArrowHashTrie$Leaf node)
                (instance? ArrowHashTrie$IidBranch node) (mapv render-trie (.getIidChildren ^ArrowHashTrie$IidBranch node))
                :else node))]

      (render-trie (-> (.getVectorSchemaRoot rdr)
                       (.getVector "nodes")
                       (ArrowHashTrie.)
                       (.getRootNode))))))

(comment
  (->> (util/->path "target/compactor/lose-data-on-compaction/objects/v02/tables/docs/meta/log-l01-nr121-rs16.arrow")
       (read-meta-file)))

(defn read-table-block-file [store-path table-name block-idx]
  (with-open [in (io/input-stream (.toFile (-> (util/->path store-path)
                                               (.resolve (trie/table-name->table-path table-name))
                                               (table-cat/->table-block-metadata-obj-key block-idx))))]
    (-> (TableBlock/parseFrom in)
        (table-cat/<-table-block)
        (update :tries
                (fn [tries]
                  (->> tries
                       (mapv (fn [^TrieDetails td]
                               {:trie-key (.getTrieKey td)
                                :trie-meta (some-> (.getTrieMetadata td)
                                                   (trie-cat/<-trie-metadata)
                                                   (update :iid-bloom
                                                           (fn [^ImmutableRoaringBitmap bloom]
                                                             (some-> bloom .serializedSizeInBytes))))}))))))))

(comment
  (read-table-block-file "/home/james/tmp/readings-bench/objects/v06" "public/readings" 0x213))
