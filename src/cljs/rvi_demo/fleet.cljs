(ns rvi-demo.fleet
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om]
            [om-tools.dom :as dom :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [ajax.core :refer (GET)]
            [om-bootstrap.grid :as g]
            [om-bootstrap.button :as b]
            [cljsjs.d3]
            [cljs.core.async :refer [<! alts! chan put! sliding-buffer]]))

(defn api-uri
  []
  (.-api_uri js/conf))

(defn get-positions
  [date cursor]
  (GET (str (api-uri) "fleet/position?time=" (.getTime date)) {:handler #(om/update! cursor [:positions] %)}))

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

(defcomponent fleet-position
  [cursor owner]

  (init-state
    [_]
    {:leaflet-map nil :tile-layer nil})

  (render-state [_ _]
    (dom/div #js {:id "map"} nil))

  (did-mount
    [_]
    (let [m (draw-map cursor)
          lurl (get-in cursor [:osm :url])
          lattr (get-in cursor [:osm :attrib])
          t (L.TileLayer. lurl #js {:minZoom 1 :maxZoom 19, :attribution lattr :id "examples.map-20v6611k"})]
      (.addLayer m t)
      (draw-pos m (:positions cursor))
      (om/set-state! owner {:leaflet-map m :tile-layer t})))

  (did-update [_ _ {:keys [leaflet-map tile-layer]}]
    (if leaflet-map
      (do
        (.eachLayer leaflet-map (fn [layer] (if (not= layer tile-layer)
                                              (.removeLayer leaflet-map layer))))
        #_(.addLayer leaflet-map tile-layer)
        (draw-pos leaflet-map (:positions cursor))))))

(defn js-time
  [y m d]
  (.getTime (js/Date. y m d)))

(defn time-scale
  []
  (-> js/d3.time
      (.scale)
      (.domain (clj->js [(js-time 2008 4 18) (js-time 2008 4 19)]))
      (.range #js [0 800])))


(defn select [selector]
  (-> js/d3 (.select selector)))

(defn append
  [sel nm attributes]
  (reduce
    (fn [acc [n v]] (.attr acc (clj->js n) (clj->js v)))
    (-> sel (.append nm) )
    (seq attributes)))

(defn slider-svg
  [selection]
  (-> selection
      (append "svg" {:id "slider-svg" :height 70})
      (append "g" {:transform "translate(10, 10)"})))

(defn brush-g
  [parent]
  (-> parent (append "g" {:class "brush" :transform "translate(20, 0)"})))

(defn mk-brush
  [scale]
  (-> js/d3.svg (.brush)
      (.x scale)
      (.extent #js [0 0])))

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
  [dst time-chan]
  (let [time (time-scale)
        axis (mk-axis time)
        svg (slider-svg dst)
        ag (axis-g svg)
        bg (brush-g svg)
        handle (-> bg (append "circle" {:class "handle" :transform "translate(0, 20)" :r 7}))
        text (-> (append svg "text" {:transform "translate(40, 60)"})
                 (.style "text-anchor", "middle")
                 (.text (format-date (js/Date. 2008 5 18))))
        brush (-> (mk-brush time) (attach-listeners handle time time-chan text))]

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
      (draw-slider (select "#slider") c))))

(defcomponent date-input
  [cursor _]
  (render
    [_]
    (dom/div {:id "date-input" :class "input-group date" :style {:margin-top "13px"}}
      (dom/input {:type "text" :class "form-control"}
               (dom/span {:class "input-group-addon"}
                         (dom/i {:class "glyphicon glyphicon-th"})))))

  (did-mount
    [_]
    (doto (js/$ "#date-input")
      (.datepicker  (clj->js {:format "dd/mm/yyyy"}))
      (.datepicker "update" (:selected-date cursor))))

  (did-update
    [_ _ _]
    (.datepicker (js/$ "#date-input") "update" (js/Date. 2008 4 18))))

(defcomponent time-picker
  [cursor _]

  (render-state [_ chans]
    (om/build slider cursor {:init-state chans})))

(defcomponent fleet
  [cursor owner]
  (init-state
    [_]
    {:chans {:time-chan (chan (sliding-buffer 1))
             :reload-chan (chan (sliding-buffer 1))}})

  (render-state [_ {:keys [chans]}]
    (dom/div
      (g/grid {}
        (g/row {}
               (om/build fleet-position cursor))
        (g/row {}
               (g/col {:md 2} (om/build date-input (:time-extent cursor)))
               (g/col {:md 9} (om/build time-picker cursor {:init-state chans}))
               (g/col {:md 1 :style {:height "75px"}})))))

  (will-mount
    [_]
    (get-positions (js/Date. 2008 4 18) cursor)
    (go-loop []
             (let [ctime (om/get-state owner [:chans :time-chan])
                   creload (om/get-state owner [:chans :reload-chan])
                   event (-> (alts! [ctime creload]) (nth 0))]
               (.log js/console event)
               (get-positions event cursor)
               (recur)))))