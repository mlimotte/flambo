(ns flambo.example.tfidf
  (:require [flambo.api :as f]
            [flambo.destructure :as fd]
            [flambo.debug :refer [inspect] :as debug]
            [flambo.conf :as conf])
  (:gen-class))

(def master "local")
(def conf {})
(def env {})

(def stopwords #{"a" "all" "and" "any" "are" "is" "in" "of" "on"
                 "or" "our" "so" "this" "the" "that" "to" "we"})

;; Returns a stopword filtered seq of
;; [doc-id term term-frequency doc-terms-count] tuples
(f/defsparkfn gen-docid-term-tuples [doc-id content]
  (let [terms (filter #(not (contains? stopwords %))
                      (clojure.string/split content #" "))
        doc-terms-count (count terms)
        term-frequencies (frequencies terms)]
    (map (fn [term] (f/tuple doc-id [term (term-frequencies term) doc-terms-count]))
         (distinct terms))))

(defn calc-idf [doc-count]
  (f/fn [term tuple-seq]
    (let [df (count tuple-seq)]
      (f/tuple term (Math/log (/ doc-count (+ 1.0 df)))))))

(defn -main [& args]
  (try
    (let [c (-> (conf/spark-conf)
                (conf/master master)
                (conf/app-name "tfidf")
                (conf/set "spark.akka.timeout" "300")
                (conf/set conf)
                (conf/set-executor-env env))
          sc (f/spark-context c)

          ;; sample docs and terms
          documents [(f/tuple "doc1" "Four score and seven years ago our fathers brought forth on this continent a new nation")
                     (f/tuple "doc2" "conceived in Liberty and dedicated to the proposition that all men are created equal")
                     (f/tuple "doc3" "Now we are engaged in a great civil war testing whether that nation or any nation so")
                     (f/tuple "doc4" "conceived and so dedicated can long endure We are met on a great battlefield of that war")]

          doc-data (f/parallelize-pairs sc documents)
          _ (inspect doc-data "doc-data")

          ;; stopword filtered RDD of [doc-id term term-freq doc-terms-count] tuples
          doc-term-seq (-> doc-data
                           (f/flat-map-to-pair (fd/tuple-fn gen-docid-term-tuples))
                           (inspect "doc-term-seq")
                           f/cache)

          ;; RDD of term-frequency tuples: [term [doc-id tf]]
          ;; where tf is per document, that is, tf(term, document)
          tf-by-doc (-> doc-term-seq
                        (f/map-to-pair (fd/tuple-fn (f/fn [doc-id [term term-freq doc-terms-count]]
                                                      (f/tuple term [doc-id (double (/ term-freq doc-terms-count))]))))
                        (inspect "tf-by-doc")
                        f/cache)

          ;; total number of documents in corpus
          num-docs (f/count doc-data)

          ;; idf of terms, that is, idf(term)
          idf-by-term (-> doc-term-seq
                          (f/group-by (fd/tuple-fn (f/fn [_ [term _ _]] term)))
                          (f/map-to-pair (fd/tuple-fn (calc-idf num-docs)))
                          (inspect "idf-by-term"))

          ;; tf-idf of terms, that is, tf(term, document) x idf(term)
          tfidf-by-term (-> (f/join tf-by-doc idf-by-term)
                            (inspect "tf-idf-by-term")
                            (f/map (fd/tuple-value-fn (f/fn [term [doc-id tf] idf]
                                                        [doc-id term (* tf idf)])))
                            f/cache)
          ]
      (->> tfidf-by-term
           f/collect
           ((partial sort-by last >))
           (take 10)
           clojure.pprint/pprint))
    (catch Exception e
      (println (.printStackTrace e)))))
