(ns rvi-demo.live-data
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [om.core :as om]
            [cljs-wsock.core :as ws]
            [cljs.reader :as reader]
            [cljs.core.async :refer [<!]]
            [om-tools.dom :as dom :include-macros true]
            [om-bootstrap.grid :as g]
            [om-bootstrap.panel :as p]
            [om-tools.core :refer-macros [defcomponent defcomponentk]]
            [secretary.core :as sec :include-macros true]
            [rvi-demo.nav :as n]))


(defn mk-map
  [cursor]
  (let [lurl (get-in cursor [:osm :url])
        lattr (get-in cursor [:osm :attrib])]
    (-> js/L (.map "map")
        (.setView #js [37.76084 -122.39522] 11)
        (.addLayer (L.TileLayer. lurl #js {:minZoom 1 :maxZoom 19, :attribution lattr})))))

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


(defn ws-connect
  [ws-channel cursor]
  (go-loop []
    (let [[status event] (<! ws-channel)]
      (case status
        :opened (do
                  (.log js/console "Connection opened")
                  (recur))
        :message (let [data (reader/read-string (str event))]
                   (om/update! cursor [:traces (:id data)] data)
                   (update-marker cursor data)
                   (recur))
        :closed (.log js/console  "Connection closed")
        :error (.log js/console  "Error!!!")))))

(defn ws-uri
  []
  (.-traces_uri js/conf))

(defcomponent map-component
  [cursor owner]
  (will-mount
    [_]
    (let [ch (ws/open! (ws-uri))]
      (ws-connect ch cursor)
      (om/set-state! owner {:ws ch})))

  (will-unmount
    [_]
    (ws/close! (om/get-state owner [:ws])))

  (render [_]
    (dom/div #js {:id "map"} nil))

  (did-mount [_]
    (.log js/console "Mounted")
    (let [leaflet (mk-map cursor)]
      (om/update! cursor [:map :leaflet-map] leaflet)))

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

(defcomponent grid
  [cursor _]
  (render
    [_]
    (dom/div
      (g/grid {}
              (g/row {}
                     (g/col {:md 6 :md-push 6}
                            (p/panel {:header "Summary"} (om/build stats-component (:traces cursor))))
                     (g/col {:md 6 :md-pull 6}
                            (p/panel {:header "Map"} (om/build map-component cursor))))
              )))
  )
