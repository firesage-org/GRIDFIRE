(ns gridfire.fetch.base
  (:require [tech.v3.datatype :as d]))

(defmulti get-wrapped-tensor-multi (fn [_context-map layer-spec _convert-fn _target-dtype] (:type layer-spec)))

(defn get-wrapped-tensor
  "Fetches a layer into a tensor wrapped in a map with geospatial metadata."
  [context-map layer-spec convert-fn target-dtype]
  {:pre [(map? context-map)
         (:type layer-spec)
         (or (nil? target-dtype)
             (keyword? target-dtype))]}
  ;; Why hide the multimethod call behind this function? (Val, 21 Oct 2022)
  ;; Because I think the semantics of the multimethod are likely to evolve,
  ;; and we don't want that to affect callers, hence the indirection.
  ;; This decouples callers from implementers.
  (get-wrapped-tensor-multi context-map layer-spec convert-fn target-dtype))

(defn convert-tensor-as-requested
  "General utility function for applying conversions and type coercions to tensors,
  useful when the data provider cannot do it directly."
  [layer-map convert-fn target-dtype]
  (let [old-tensor        (:matrix layer-map)
        old-dtype         (d/elemwise-datatype old-tensor)
        needs-converting? (or (some? convert-fn)
                              (and (some? target-dtype)
                                   (not= target-dtype old-dtype)))]
    (if needs-converting?
      (let [converted  (d/emap (or convert-fn identity)
                               target-dtype
                               old-tensor)
            new-tensor (if (= old-dtype target-dtype)
                         (do
                           ;; converts in-place when possible, as it's more efficient.
                           (d/copy! converted old-tensor)
                           ;; Redundant since the above (d/copy! ...) already returns old-tensor,
                           ;; but I'd rather be very explicit about mutation. (Val, 04 Nov 2022)
                           old-tensor)
                         (d/clone converted))]
        (assoc layer-map :matrix new-tensor))
      layer-map)))