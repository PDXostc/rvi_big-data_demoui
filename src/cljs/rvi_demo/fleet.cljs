(ns rvi-demo.fleet
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om]
            [om-tools.dom :as dom :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [ajax.core :refer (GET)]
            [om-bootstrap.grid :as g]
            [om-bootstrap.button :as b]
            [cljsjs.d3]
            [rvi-demo.datepicker :as dp]
            [cljs.core.async :refer [<! alts! chan put! sliding-buffer]])
  (:use [cljs-time.coerce :only (from-date to-long)]
        [cljs-time.core :only (days plus)]))

(defn api-uri
  []
  (.-api_uri js/conf))

(defn get-positions
  [date area cursor]
  (GET (str (api-uri) "fleet/position")
       {:params  {:time (.getTime date)
                  :area area}
        :handler #(om/update! cursor [:positions] %)}))

(defn mk-marker
  [data lat-lng]
  (let [occupied? (-> data (js->clj {:keywordize-keys true}) (get-in ["properties" "isOccupied"]))]
    (-> js/L (.circleMarker lat-lng #js {:radius 3,
                                       :fillColor (if occupied? "#FA0E02" "#02FA0A" ),
                                       :color "#000",
                                       :weight 1,
                                       :opacity 1,
                                       :fillOpacity 0.8}))))

(defn draw-pos
  [map data]
  (-> js/L (.geoJson (clj->js data) #js {:pointToLayer mk-marker}) (.addTo map)))

(defn draw-map
  [cursor]
  (-> js/L (.map "map")
      (.setView #js [37.76084 -122.39522] 11)))

(defn select [selector]
  (-> js/d3 (.select selector)))

(defn append
  [sel nm attributes]
  (reduce
    (fn [acc [n v]] (.attr acc (clj->js n) (clj->js v)))
    (-> sel (.append nm) )
    (seq attributes)))

(defcomponent fleet-position
  [cursor owner]

  (init-state
    [_]
    {:leaflet-map nil :tile-layer nil})

  (render-state [_ _]
    (dom/div {:id "map"}))

  (did-mount
    [_]
    (let [m (draw-map cursor)
          lurl (get-in cursor [:osm :url])
          lattr (get-in cursor [:osm :attrib])
          t (L.TileLayer. lurl #js {:minZoom 1 :maxZoom 19, :attribution lattr :id "examples.map-20v6611k"})
          filter-layer (js/L.FeatureGroup.)]
      (.addLayer m t)
      (draw-pos m (:positions cursor))
      (.addLayer m filter-layer)
      (.addControl m (js/L.Control.Draw. (clj->js {:edit {:featureGroup filter-layer}})))
      (.on m "draw:created" (fn [e]
                              (let [date (get-in @cursor [:time-extent :selected-date 0])
                                    area (map (fn [lat-lng] [(.-lat lat-lng) (.-lng lat-lng)]) (-> e (.-layer) (.getLatLngs)))]
                                (om/update! cursor [:area] (vec area))
                                (get-positions date area cursor))
                              (.addLayer filter-layer (.-layer e))))
      (om/set-state! owner {:leaflet-map m :tile-layer t :filter-layer filter-layer})))

  (did-update [_ _ {:keys [leaflet-map tile-layer filter-layer]}]
    (if leaflet-map
      (do
        (.eachLayer leaflet-map (fn [layer] (if (and
                                                  (not (contains? #{tile-layer filter-layer} layer))
                                                  (not (.hasLayer filter-layer layer)))
                                              (.removeLayer leaflet-map layer))))
        #_(.addLayer leaflet-map tile-layer)
        (draw-pos leaflet-map (:positions cursor))))))

(defn time-scale
  [date-time]
  (prn "scale date " date-time)
  (let [date (js/Date. (.getFullYear date-time) (.getMonth date-time) (.getDate date-time))]
    (-> js/d3.time
        (.scale)
      (.domain (clj->js [(.getTime date) (to-long (plus (from-date date) (days 1)))]))
      (.range #js [0 800]))))



(defn slider-svg
  [selection]
  (-> selection
      (append "svg" {:id "slider-svg" :height 70})
      (append "g" {:transform "translate(10, 10)"})))

(defn brush-g
  [parent]
  (-> parent (append "g" {:class "brush" :transform "translate(20, 0)"})))

(defn mk-brush
  [scale date-time]
  (prn (str "Brush date time " date-time " pos " (scale (.getTime date-time))))
  (-> js/d3.svg (.brush)
      (.x scale)
      (.extent #js [0 (scale (.getTime date-time))])))

(defn format-date
  [date]
  ((.format js/d3.time "%I:%M %p") date))

(defn pos-time
  [brush scale]
  (let [time (-> brush (.extent) (js->clj) (nth 1) (clj->js))
        pos (scale time)]
    [pos time]))

(defn attach-listeners
  [brush handle scale time-chan text]
  (let [brush-handler (fn [] (let [[pos time] (pos-time brush scale)]
                         (.attr handle "cx" pos)
                         (.text text (format-date time))))
        brushend-handler (fn [] (let [[_ time] (pos-time brush scale)]
                                  (put! time-chan time)))]
    (-> brush (.on "brush" brush-handler) (.on "brushend" brushend-handler))))

(defn axis-g
  [parent]
  (-> parent (append "g" {:class "x axis" :transform "translate(20, 20)"})))

(defn mk-axis [scale]
  (-> js/d3.svg (.axis)
      (.scale scale)
      (.ticks js/d3.time.hour 2)))

(defn draw-slider
  [dst date time-chan]
  (let [time (time-scale date)
        axis (mk-axis time)
        svg (slider-svg dst)
        ag (axis-g svg)
        bg (brush-g svg)
        handle (-> bg (append "circle" {:class "handle" :transform "translate(0, 20)" :r 7}))
        text (-> (append svg "text" {:transform "translate(40, 60)"})
                 (.style "text-anchor", "middle")
                 (.text (format-date date)))
        brush (-> (mk-brush time date) (attach-listeners handle time time-chan text))]
    (.attr handle "cx" (time date))
    (-> bg (.selectAll ".extent,.resize") (.remove))
    (axis ag)
    (brush bg)
    (-> bg (.select ".background") (.attr "height" 50))))

(defcomponent slider
  [cursor owner]

  (render [_]
    (dom/div #js {:id "slider"}))

  (did-mount [_]
    (let [c (om/get-state owner [:time-chan])]
      (draw-slider (select "#slider") (get cursor 0) c)))

  (did-update [_ _ _]
    (-> (select "#slider") (.select "svg") (.remove))
    (let [c (om/get-state owner [:time-chan])]
      (draw-slider (select "#slider") (get cursor 0) c))))

(defcomponent time-picker
  [cursor _]

  (render-state [_ chans]
    (om/build slider cursor {:init-state chans})))

(defcomponent fleet
  [cursor owner]

  (init-state
    [_]
    {:chans {:time-chan   (chan (sliding-buffer 1))
             :reload-chan (chan (sliding-buffer 1))}})

  (render-state [_ {:keys [chans]}]
    (dom/div
      (g/grid {}
              (g/row {}
                     (om/build fleet-position cursor))
              (g/row {}
                     (g/col {:md 2}
                            (om/build dp/datepicker
                                      (get-in cursor [:time-extent :selected-date])
                                      {:opts {:id        "datepick"
                                              :on-change (fn [e]
                                                           (prn (.-date e)))}}))
                     (g/col {:md 9} (om/build slider (get-in cursor [:time-extent :selected-date]) {:init-state chans}))
                     (g/col {:md 1 :style {:height "75px"}})))))

  (will-mount
    [_]
    (go-loop []
             (let [ctime (om/get-state owner [:chans :time-chan])
                   creload (om/get-state owner [:chans :reload-chan])
                   event (-> (alts! [ctime creload]) (nth 0))
                   area (get-in @cursor [:area])]
               (om/update! cursor [:time-extent :selected-date] [event])
               (get-positions event area cursor)
               (recur)))))