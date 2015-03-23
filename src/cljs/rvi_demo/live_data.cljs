(ns rvi-demo.live-data
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [om.core :as om]
            [cljs-wsock.core :as ws]
            [cljs.reader :as reader]
            [cljs.core.async :refer [<!]]
            [om-tools.dom :as dom :include-macros true]
            [om-bootstrap.grid :as g]
            [om-bootstrap.panel :as p]
            [cljsjs.d3]
            [om-tools.core :refer-macros [defcomponent defcomponentk]]
            [cljs.core.async :refer [<! alts! chan put! sliding-buffer]]
            [cognitect.transit :as t]
            [secretary.core :as sec :include-macros true]
            [rvi-demo.map :as map]
            [rvi-demo.nav :as n]))


(defn marker-icon
  [occupied?]
  (js/L.icon. (clj->js {:iconUrl (if occupied? "occupied.png" "free.png")
                        :iconSize    [25, 41]
                        :iconAnchor  [12, 41]
                        :popupAnchor [1, -34]
                        :shadowSize  [41, 41]})))

(defn mk-marker
  [map data]
  (-> js/L
      (.marker (clj->js data) (clj->js {:icon (marker-icon (:occupied data))}))
      (.bindPopup (str "<p>" (:id data) "</p>"))
      (.addTo map)))

(defn update-marker
  [cursor event]
  (when-let [ map (get-in @cursor [:map :leaflet-map])]
    (let [trace-id (get event :id)
        marker (get-in @cursor [:markers trace-id])
        pos (clj->js event)]
      (if marker
        (-> marker (.setLatLng pos) (.setIcon (marker-icon (:occupied event))) (.update))
        (om/update! cursor [:markers trace-id] (mk-marker map event))))))

(defn ws-uri
  []
  (.-traces_uri js/conf))

(defcomponent map-component
  [cursor owner {:keys [area]}]
  (:mixins map/leaflet-map)

  (init-state
    [_]
    {:area area})

  (did-mount
    [_]
    (let [markers (.layerGroup js/L)
          m (.mk-map owner "map" [markers])]
      (om/update! cursor [:map :leaflet-map] markers)))

  (render [_]
    (dom/div #js {:id "map"} nil))

  (should-update
    [_ _ _]
    false)
  )

(defn stats-component
  [cursor _]
  (om/component
    (let [total (count cursor)
          occupied (count (filter #(get-in % [1 :occupied]) cursor))
          free (- total occupied)]
      (dom/div
        (dom/p (dom/strong "Number of taxis") " : " total)
        (dom/p (dom/strong {:class "text-success"} "Free") " : " free)
        (dom/p (dom/strong {:class "text-danger"} "Occupied") " : " occupied)))))

(defn- speed-scale
  [domain]
  (-> d3.scale
      (.linear)
      (.range #js [0 800])
      (.domain (clj->js domain))))

(defn- draw-axis
  [parent scale]
  ((-> d3.svg (.axis) (.scale scale) (.orient "bottom")) parent))

(defn select [selector]
  (-> js/d3 (.select selector)))

(defn append
  [sel nm attributes]
  (reduce
    (fn [acc [n v]] (.attr acc (clj->js n) (clj->js v)))
    (-> sel (.append nm) )
    (seq attributes)))

(defcomponent axis
  [cursor owner]
  (render
    [_]
    (dom/g {:class "x axis" :transform "translate(20, 20)"}))
  (did-mount
    [_]
    (draw-axis (select ".x") (speed-scale cursor))))

(defn- arc
  []
  (-> d3.svg
      (.arc)
      (.outerRadius 10)
      (.startAngle 0)
      (.endAngle (fn [_ i] (if (= i 1) (- js/Math.PI) js/Math.PI)))))

(defn round
  [d]
  (let [factor (Math/pow 10 0)]
    (/ (Math/floor (* d factor)) factor)))

(defn- brushcenter
  [brush scale brushg]
  (fn []
    (let [extent (js->clj (.extent brush))
          size (- (extent 1) (extent 0))
          target (.-target js/d3.event )
          pos (round (.invert scale ((js->clj (.mouse js/d3 target)) 0)))
          x0 (round (- pos (/ size 2)))
          x1 (round (+ pos (/ size 2)))
          domain (-> scale (.-domain) (js->clj))
          new-extent (cond
                       (< x0 (domain 0)) [0 size]
                       (> x1 (domain 1)) [(- (domain 1) size) (domain 1)]
                       :else [x0 x1])]
      (.stopPropagation (.-event js/d3))
      (((.-extent brush) (clj->js new-extent)) brushg)
      ((.-event brush) brushg))))

(defcomponent speed-interval
  [cursor owner]

  (render
    [_]
    (dom/g {:class "brush"}))

  (did-mount
    [_]
    (let [x-scale (speed-scale (:range cursor))
          brush (-> d3.svg (.brush) (.x x-scale) (.extent (clj->js (:value cursor))))
          target (->> owner (om/get-node) (.select js/d3))]
      (brush target)
      (append (.selectAll target ".resize") "path" {:transform "translate(20, 9)" :d (arc)})
      (-> (.selectAll target "rect") (.attr "height" 18) (.attr "transform" "translate (20, 0)"))
      (.on brush "brushend"
           (fn []
             (if (.-sourceEvent js/d3.event)
               (this-as this
                        (let [ext (map round (js->clj (.extent brush)) )]
                          (-> this (select)
                              (.transition)
                              (.call ((.-extent brush) (clj->js ext)))
                              (.call (.-event brush)))
                          (put!
                            (om/get-state owner :speed-channel)
                            (js->clj (.extent brush))))))))
      (-> (.select target ".background") (.on "mousedown.brush" (brushcenter brush x-scale target))))))

(defcomponent speed-filter
  [cursor _]

  (render-state
    [_ state]
    (dom/div {:id "speed-filter"}
             (dom/svg {:width 900 :height 100}
                      (dom/g {:transform "translate(20, 20)"}
                             (om/build axis (get-in cursor [:filter :speed :range]))
                             (om/build speed-interval (get-in cursor [:filter :speed]) {:init-state state}))))))

(def js-writer (t/writer :json-verbose))

(defn- filter-traces
  [cursor f]
  (let [traces (get-in @cursor [:traces])
        expired (->> traces (filter f) (map first))]
    (if (seq expired)
      (om/transact! cursor (fn [c]
                             (let [m (get-in c [:map :leaflet-map])]
                               (doall
                                 (map #(.removeLayer m (second %)) (select-keys (:markers c) expired)))
                               (-> c
                                   (update-in [:traces] (fn [t] (apply dissoc t expired)))
                                   (update-in [:markers] (fn [t] (apply dissoc t expired))))) )))))

(defcomponent live-data
  [cursor owner]

  (init-state
    [_]
    {:chans {:speed-channel (chan (sliding-buffer 1))
             :gps-data      (ws/open! (ws-uri))
             :timer         (chan (sliding-buffer 1))
             :area          (chan (sliding-buffer 1))}})

  (will-mount
    [_]
    (let [{:keys [speed-channel gps-data timer area]} (om/get-state owner :chans)]
      (js/setInterval #(put! timer (.getTime (js/Date.))) 5000)
      (go-loop []
               (let [[value port] (alts! [speed-channel gps-data timer area])]
                 (condp = port
                   gps-data (let [[status event] value]
                              (case status
                                :opened (do
                                          (.log js/console "Connection opened")
                                          (recur))
                                :message (let [data (reader/read-string (str event))]
                                           (om/update! cursor [:traces (:id data)] (merge data {:timestamp (.getTime (js/Date.))}))
                                           (update-marker cursor data)
                                           (recur))
                                :closed (.log js/console "Connection closed")
                                :error (.log js/console "Error!!!")))
                   speed-channel (do
                                   (ws/send gps-data (t/write js-writer (zipmap ["min" "max"] value)))
                                   (om/transact! cursor #(merge % {:traces {} :markers {}}))
                                   (.clearLayers (get-in @cursor [:map :leaflet-map]))
                                   (recur))
                   timer (do
                           (filter-traces cursor #(< 15000 (- value (:timestamp (second %)))))
                           (recur))
                   area (do
                          (filter-traces cursor (fn [trace]
                                                  (not (-> value (.getBounds) (.contains (->> trace (second) (clj->js) (.latLng js/L)))))))
                          (ws/send gps-data (t/write js-writer (apply array (map #(zipmap ["lat" "lng"] %) (-> value (map/polygon->coord-vec) )))))
                          (recur)))))))

  (will-unmount
    [_]
    (ws/close! (om/get-state owner [:gps-data])))

  (render-state
    [_ {:keys [chans]}]
    (dom/div
      (g/grid {}
              (g/row {}
                     (g/col {:md 9 }
                            (p/panel {:header "Map"} (->map-component cursor {:opts chans})))
                     (g/col {:md 3 }
                            (p/panel {:header "Summary"} (om/build stats-component (:traces cursor)))))
              (g/row {}
                     (g/col {}
                            (om/build speed-filter cursor {:init-state chans})))))))
