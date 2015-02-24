(ns rvi-demo.datepicker
  (:require [om.core :as om]
            [om-tools.dom :as dom :include-macros true]))

(defn datepicker
  [cursor owner {:keys [id on-change]}]
  (reify
    om/IRender
    (render
      [_]
      (dom/div {:id id :class "input-group date" :style {:margin-top "13px"}}
               (dom/input {:type "text" :class "form-control"}
                          (dom/span {:class "input-group-addon"}
                                    (dom/i {:class "glyphicon glyphicon-th"})))))

    om/IDidMount
    (did-mount
      [_]
      (doto (js/$ (str "#" id))
        (.datepicker  (clj->js {:format "dd/mm/yyyy" :autoclose true}))
        (.datepicker "update" (get cursor 0))
        (-> (.datepicker) (.on "changeDate" (fn [e]
                                              (on-change e)
                                              (om/transact! cursor [0] #(.-date e)))))))))