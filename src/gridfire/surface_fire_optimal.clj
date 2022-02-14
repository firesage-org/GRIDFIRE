(ns gridfire.surface-fire-optimal
  (:require [tech.v3.tensor :as t]))

(defn map-category [f]
  {:dead (f :dead) :live (f :live)})

(defn map-size-class [f]
  {:dead {:1hr        (f :dead :1hr)
          :10hr       (f :dead :10hr)
          :100hr      (f :dead :100hr)
          :herbaceous (f :dead :herbaceous)}
   :live {:herbaceous (f :live :herbaceous)
          :woody      (f :live :woody)}})

(defn category-sum [f]
  (+ (f :dead) (f :live)))

(defn size-class-sum [f]
  {:dead (+ (f :dead :1hr) (f :dead :10hr) (f :dead :100hr) (f :dead :herbaceous))
   :live (+ (f :live :herbaceous) (f :live :woody))})

;; S_e_i is bound to a map of {:dead (+ (* f_ij S_e) (* f_ij S_e) (* f_ij S_e) (* f_ij S_e) :live}
;; Let's store these input values in records of tensors {:dead [:1hr :10hr :100hr :herbaceous] :live [:herbaceous :woody]}

(defrecord SizeClassTensors [^tech.v3.tensor_api.DirectTensor dead
                             ^tech.v3.tensor_api.DirectTensor live])

#_(SizeClassValues. (t/->tensor [1 10 100 0]) (t/->tensor [1000 10000]))

(defrecord FuelModel [^long number
                      ^double delta
                      ^SizeClassTensors w_o
                      ^SizeClassTensors sigma
                      ^SizeClassTensors h
                      ^SizeClassTensors rho_p
                      ^SizeClassTensors S_T
                      ^SizeClassTensors S_e
                      ^SizeClassTensors M_x
                      ^SizeClassTensors M_f
                      ^SizeClassTensors f_ij
                      ^SizeClassTensors f_i
                      ^SizeClassTensors g_ij])

(defn calc-mineral-damping-coefficients [f_ij S_e]
  (let [S_e_i (size-class-sum (fn [i j] (* (-> f_ij i j) (-> S_e i j))))]
    (map-category (fn [i] (let [S_e_i (-> S_e_i i)]
                            (if (pos? S_e_i)
                              (/ 0.174 (Math/pow S_e_i 0.19))
                              1.0))))))

(defn calc-moisture-damping-coefficients [f_ij M_f M_x]
  (let [M_f_i (size-class-sum (fn [i j] (* (-> f_ij i j) (-> M_f i j))))
        M_x_i (size-class-sum (fn [i j] (* (-> f_ij i j) (-> M_x i j))))
        r_M_i (map-category (fn [i] (let [M_f (-> M_f_i i)
                                          M_x (-> M_x_i i)]
                                      (if (pos? M_x)
                                        (min 1.0 (/ M_f M_x))
                                        1.0))))]
    (map-category (fn [i] (+ 1.0
                             (* -2.59 (-> r_M_i i))
                             (* 5.11 (Math/pow (-> r_M_i i) 2))
                             (* -3.52 (Math/pow (-> r_M_i i) 3)))))))

(defn calc-low-heat-content [f_ij h]
  (size-class-sum (fn [i j] (* (-> f_ij i j) (-> h i j)))))

(defn calc-net-fuel-loading [g_ij w_o S_T]
  (size-class-sum (fn [i j] (* (-> g_ij i j)
                               (-> w_o i j)
                               (- 1.0 (-> S_T i j))))))

(defn calc-packing-ratio [w_o rho_p delta]
  (let [beta_i (size-class-sum (fn [i j] (/ (-> w_o i j) (-> rho_p i j))))]
    (if (pos? delta)
      (/ (category-sum (fn [i] (-> beta_i i))) delta)
      0.0)))

(defn calc-surface-area-to-volume-ratio [f_i f_ij sigma]
  (let [sigma'_i (size-class-sum (fn [i j] (* (-> f_ij i j) (-> sigma i j))))]
    (category-sum (fn [i] (* (-> f_i i) (-> sigma'_i i))))))

(defn calc-optimum-packing-ratio [sigma']
  (if (pos? sigma')
    (/ 3.348 (Math/pow sigma' 0.8189))
    1.0))

(defn calc-reaction-intensity [eta_S_i eta_M_i h_i W_n_i beta sigma' beta_op]
  (let [;; Albini 1976 replaces (/ 1 (- (* 4.774 (Math/pow sigma' 0.1)) 7.27))
        A          (if (pos? sigma')
                     (/ 133.0 (Math/pow sigma' 0.7913))
                     0.0)
        ;; Maximum reaction velocity (1/min)
        Gamma'_max (/ (Math/pow sigma' 1.5)
                      (+ 495.0 (* 0.0594 (Math/pow sigma' 1.5))))
        ;; Optimum reaction velocity (1/min)
        Gamma'     (* Gamma'_max
                      (Math/pow (/ beta beta_op) A)
                      (Math/exp (* A (- 1.0 (/ beta beta_op)))))]
    (* Gamma' (category-sum (fn [i] (* (W_n_i i) (h_i i) (eta_M_i i) (eta_S_i i)))))))

(defn calc-propagating-flux-ratio [beta sigma']
  (/ (Math/exp (* (+ 0.792 (* 0.681 (Math/pow sigma' 0.5)))
                  (+ beta 0.1)))
     (+ 192.0 (* 0.2595 sigma'))))

(defn calc-heat-of-preignition [M_f]
  (map-size-class (fn [i j] (+ 250.0 (* 1116.0 (-> M_f i j))))))

(defn calc-mystery-term [sigma Q_ig f_ij]
  (size-class-sum (fn [i j] (let [sigma_ij (-> sigma i j)
                                  Q_ig_ij  (-> Q_ig i j)]
                              (if (pos? sigma_ij)
                                (* (-> f_ij i j)
                                   (Math/exp (/ -138 sigma_ij))
                                   Q_ig_ij)
                                0.0)))))

(defn calc-ovendry-bulk-density [w_o delta]
  (let [rho_b_i (size-class-sum (fn [i j] (-> w_o i j)))]
    (if (pos? delta)
      (/ (category-sum (fn [i] (-> rho_b_i i))) delta)
      0.0)))

(defn calc-surface-fire-spread-rate [I_R xi foo_i rho_b f_i]
  (let [rho_b-epsilon-Q_ig (* rho_b (category-sum (fn [i] (* (-> f_i i) (-> foo_i i)))))]
    (if (pos? rho_b-epsilon-Q_ig)
      (/ (* I_R xi) rho_b-epsilon-Q_ig)
      0.0)))

(defn- grass-fuel-model?
  [^long number]
  (and (> number 100) (< number 110)))

;; Addition proposed by Chris Lautenberger (REAX 2015)
(defn calc-suppressed-spread-rate [R number grass-suppression?]
  (let [spread-rate-multiplier (if (and grass-suppression? (grass-fuel-model? number)) 0.5 1.0)]
    (* R spread-rate-multiplier)))

(defn calc-residence-time [sigma']
  (/ 384.0 sigma'))

(defn get-wind-and-slope-fns [beta beta_op sigma']
  (let [E          (* 0.715 (Math/exp (* -3.59 (/ sigma' 10000.0))))

        B          (* 0.02526 (Math/pow sigma' 0.54))

        C          (* 7.47 (Math/exp (* -0.133 (Math/pow sigma' 0.55))))

        ;; Derive wind factor
        get-phi_W  (fn ^double [^double midflame-wind-speed]
                     (if (and (pos? beta) (pos? midflame-wind-speed))
                       (-> midflame-wind-speed
                           (Math/pow B)
                           (* C)
                           (/ (Math/pow (/ beta beta_op) E)))
                       0.0))

        ;; Derive wind speed from wind factor
        get-wind-speed (fn [^double phi_W]
                         (-> phi_W
                             (* (Math/pow (/ beta beta_op) E))
                             ^double (/ C)
                             (Math/pow (/ 1.0 B))))

        ;; Derive slope factor
        get-phi_S  (fn [^double slope]
                     (if (and (pos? beta) (pos? slope))
                       (* 5.275 (Math/pow beta -0.3) (Math/pow slope 2.0))
                       0.0))]
    {:get-phi_W      get-phi_W
     :get-phi_S      get-phi_S
     :get-wind-speed get-wind-speed}))

(defn rothermel-surface-fire-spread-no-wind-no-slope
  "Returns the rate of surface fire spread in ft/min and the reaction
   intensity (i.e., amount of heat output) of a fire in Btu/ft^2*min
   given a map containing these keys:
   - number [fuel model number]
   - delta [fuel depth (ft)]
   - w_o [ovendry fuel loading (lb/ft^2)]
   - sigma [fuel particle surface-area-to-volume ratio (ft^2/ft^3)]
   - h [fuel particle low heat content (Btu/lb)]
   - rho_p [ovendry particle density (lb/ft^3)]
   - S_T [fuel particle total mineral content (lb minerals/lb ovendry wood)]
   - S_e [fuel particle effective mineral content (lb silica-free minerals/lb ovendry wood)]
   - M_x [moisture content of extinction (lb moisture/lb ovendry wood)]
   - M_f [fuel particle moisture content (lb moisture/lb ovendry wood)]
   - f_ij [percent of load per size class (%)]
   - f_i [percent of load per category (%)]
   - g_ij [percent of load per size class from Albini_1976_FIREMOD, page 20]"
  [{:keys [number delta w_o sigma h rho_p S_T S_e M_x M_f f_ij f_i g_ij]} & [grass-suppression?]]
  (let [eta_S_i (calc-mineral-damping-coefficients f_ij S_e)
        eta_M_i (calc-moisture-damping-coefficients f_ij M_f M_x)
        h_i     (calc-low-heat-content f_ij h)
        W_n_i   (calc-net-fuel-loading g_ij w_o S_T) ; (lb/ft^2)
        beta    (calc-packing-ratio w_o rho_p delta)
        sigma'  (calc-surface-area-to-volume-ratio f_i f_ij sigma)
        beta_op (calc-optimum-packing-ratio sigma')
        I_R     (calc-reaction-intensity eta_S_i eta_M_i h_i W_n_i beta sigma' beta_op) ; (Btu/ft^2*min)
        xi      (calc-propagating-flux-ratio beta sigma')
        Q_ig    (calc-heat-of-preignition M_f) ; (Btu/lb)
        foo_i   (calc-mystery-term sigma Q_ig f_ij)
        rho_b   (calc-ovendry-bulk-density w_o delta) ; (lb/ft^3)
        R       (calc-surface-fire-spread-rate I_R xi foo_i rho_b f_i) ; (ft/min)
        R'      (calc-suppressed-spread-rate R number grass-suppression?)
        t_res   (calc-residence-time sigma')]
    (-> (get-wind-and-slope-fns beta beta_op sigma')
        (assoc :spread-rate        R'
               :reaction-intensity I_R
               :residence-time     t_res))))
