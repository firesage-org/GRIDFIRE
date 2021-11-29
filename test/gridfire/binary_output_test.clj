(ns gridfire.binary-output-test
  (:require [clojure.core.matrix :as m]
            [clojure.test :refer [deftest is use-fixtures]]
            [gridfire.utils.test :as utils]
            [gridfire.binary-output :as binary]))

;;-----------------------------------------------------------------------------
;; Fixtures
;;-----------------------------------------------------------------------------

(use-fixtures :once utils/with-temp-output-dir)

;;-----------------------------------------------------------------------------
;; Tests
;;-----------------------------------------------------------------------------

(def binary-file (utils/out-file-path "toa_test.bin"))

(deftest ^:unit write-matrix-as-binary-test
  (let [matrix [[0.0 1.0 2.0] [3.0 0.0 4.0] [5.0 6.0 0.0]]]
    (binary/write-matrix-as-binary matrix binary-file)
    (is (= (m/matrix matrix) (binary/read-matrix-as-binary binary-file)))))

(deftest ^:unit write-matrices-as-binary-test
  (let [matrices [[[0.0 1.0 2.0]
                   [3.0 0.0 4.0]
                   [5.0 6.0 0.0]]

                  [[0.0 1.0 1.0]
                   [1.0 0.0 1.0]
                   [1.0 1.0 0.0]]

                  [[0.0 2.0 2.0]
                   [2.0 0.0 2.0]
                   [2.0 2.0 0.0]]

                  [[0 3 3]
                   [3 0 3]
                   [3 3 0]]]
        _        (binary/write-matrices-as-binary binary-file
                                                  [:float :float :float :int]
                                                  matrices)
        result   (binary/read-matrices-as-binary binary-file [:float :float :float :int])]
    (is (= (m/matrix (first matrices)) (first result)))

    (is (= (m/matrix (second matrices)) (second result)))

    (is (= (m/matrix (nth matrices 2)) (nth result 2)))

    (is (= (m/matrix (nth matrices 3)) (nth result 3)))))
