(ns gridfire.utils.test
  (:require [clojure.java.io :as io]
            [clojure.string  :as str]
            [gridfire.core   :as core]))

;;-----------------------------------------------------------------------------
;; Config
;;-----------------------------------------------------------------------------

(def input-dirname  "test/data/")
(def output-dirname "test/output/")

;;-----------------------------------------------------------------------------
;; Utils
;;-----------------------------------------------------------------------------

(defn in-file-path [dir filename]
  (str/join "/" [dir filename]))

(defn out-file-path [filename]
  (str output-dirname filename))

(defn make-directory [dirname]
  (.mkdir (io/file dirname)))

(defn delete-directory [dirname]
  (doseq [file (reverse (file-seq (io/file dirname)))]
    (io/delete-file file)))

(defn run-gridfire! [config]
  (some-> config
          (core/load-inputs!)
          (core/run-simulations!)
          (core/write-outputs!)))

;;-----------------------------------------------------------------------------
;; Fixtures
;;-----------------------------------------------------------------------------

(defn with-temp-output-dir [test-fn]
  (make-directory output-dirname)
  (test-fn)
  (delete-directory output-dirname))
