(ns portal.ui.inspector
  (:refer-clojure :exclude [coll? map?])
  (:require ["anser" :as anser]
            ["react" :as react]
            [lambdaisland.deep-diff2.diff-impl :as diff]
            [portal.colors :as c]
            [portal.runtime.cson :as cson]
            [portal.ui.api :as api]
            [portal.ui.filter :as f]
            [portal.ui.lazy :as l]
            [portal.ui.rpc.runtime :as rt]
            [portal.ui.select :as select]
            [portal.ui.state :as state]
            [portal.ui.styled :as s]
            [portal.ui.theme :as theme]
            [reagent.core :as r]))

(defn- inspect-error [error]
  (let [theme (theme/use-theme)]
    [s/div
     {:style
      {:color         (::c/exception theme)
       :border        [1 :solid (::c/exception theme)]
       :background    (str (::c/exception theme) "22")
       :border-radius (:border-radius theme)}}
     [s/div
      {:style
       {:padding       (:padding theme)
        :border-bottom [1 :solid (::c/exception theme)]}}
      "Rendering error: " (.-message error)]
     [s/div
      {:style
       {:padding (:padding theme)}}
      [:pre
       [:code (.-stack error)]]]]))

(defn- inspect-error* [error] [:f> inspect-error error])

(def error-boundary
  (r/create-class
   {:display-name "ErrorBoundary"
    :constructor
    (fn [this _props]
      (set! (.-state this) #js {:error nil}))
    :component-did-catch
    (fn [_this _e _info])
    :get-derived-state-from-error
    (fn [error] #js {:error error})
    :render
    (fn [this]
      (if-let [error (.. this -state -error)]
        (r/as-element [inspect-error* error])
        (.. this -props -children)))}))

(defonce viewers api/viewers)

(defn viewers-by-name [viewers]
  (into {} (map (juxt :name identity) viewers)))

(defn get-compatible-viewers [viewers {:keys [value] :as context}]
  (let [by-name        (viewers-by-name viewers)
        default-viewer (get by-name
                            (or (:portal.viewer/default (meta value))
                                (:portal.viewer/default context)))
        viewers        (cons default-viewer (remove #(= default-viewer %) viewers))]
    (filter #(when-let [pred (:predicate %)] (pred value)) viewers)))

(defn get-viewer [state context]
  (if-let [selected-viewer
           (get-in @state [:selected-viewers (state/get-location context)])]
    (some #(when (= (:name %) selected-viewer) %) @api/viewers)
    (first (get-compatible-viewers @api/viewers context))))

(defn set-viewer! [state context viewer-name]
  (state/dispatch! state
                   assoc-in [:selected-viewers (state/get-location context)]
                   viewer-name))

(def ^:private inspector-context (react/createContext {:depth 0 :path []}))

(defn use-context [] (react/useContext inspector-context))

(defn with-depth [& children]
  (let [context (use-context)]
    (into [:r> (.-Provider inspector-context)
           #js {:value (assoc context :depth 0)}] children)))

(defn inc-depth [& children]
  (let [context (use-context)]
    (into [:r> (.-Provider inspector-context)
           #js {:value (update context :depth inc)}]
          children)))

(defn dec-depth [& children]
  (let [context (use-context)]
    (into [:r> (.-Provider inspector-context)
           #js {:value (update context :depth dec)}]
          children)))

(defn- use-depth [] (:depth (use-context)))

(defn with-context [value & children]
  (let [context (use-context)]
    (into
     [:r> (.-Provider inspector-context)
      #js {:value (merge context value)}] children)))

(defn with-default-viewer [viewer & children]
  (into [with-context {:portal.viewer/default viewer}] children))

(defn with-collection [coll & children]
  (into [with-context
         {:key nil
          :collection (:portal.ui.filter/value (meta coll) coll)}]
        children))

(defn with-key [k & children]
  (let [context (use-context)
        path    (get context :path [])]
    (into [with-context {:key k :path (conj path k)}] children)))

(defn with-readonly [& children]
  (into [with-context {:readonly? true}] children))

(defonce ^:private options-context (react/createContext nil))

(defn use-options [] (react/useContext options-context))

(defn- with-options [options & children]
  (into [:r> (.-Provider options-context) #js {:value options}] children))

(defn date? [value] (instance? js/Date value))
(defn url? [value] (instance? js/URL value))
(defn bin? [value] (instance? js/Uint8Array value))
(defn bigint? [value] (= (type value) js/BigInt))
(defn error? [value] (instance? js/Error value))

(defn coll? [value]
  (and (clojure.core/coll? value)
       (not (cson/tagged-value? value))))

(defn map? [value]
  (and (clojure.core/map? value)
       (not (cson/tagged-value? value))))

(defn get-value-type [value]
  (cond
    (tagged-literal? value)
    :tagged

    (cson/tagged-value? value)
    (:tag value)

    (instance? diff/Deletion value)   :diff
    (instance? diff/Insertion value)  :diff
    (instance? diff/Mismatch value)   :diff

    (bin? value)      :binary

    (map? value)      :map
    (set? value)      :set
    (vector? value)   :vector
    (list? value)     :list
    (coll? value)     :coll
    (boolean? value)  :boolean
    (symbol? value)   :symbol
    (bigint? value)   :bigint
    (number? value)   :number
    (string? value)   :string
    (keyword? value)  :keyword
    (var? value)      :var
    (error? value)    :error

    (uuid? value)     :uuid
    (url? value)      :uri
    (date? value)     :date

    (rt/runtime? value)
    (rt/tag value)))

(declare inspector)
(declare preview)

(defn get-background
  ([]
   (get-background (use-depth)))
  ([depth]
   (let [theme (theme/use-theme)]
     (if (even? depth)
       (::c/background theme)
       (::c/background2 theme)))))

(defn ->id [value]
  (str (hash value) (type value)))

(defn tabs [value]
  (let [theme   (theme/use-theme)
        options (keys value)
        [option set-option!] (react/useState (first options))
        background (get-background)]
    [s/div
     {:style
      {:background background
       :border [1 :solid (::c/border theme)]
       :border-radius (:border-radius theme)}}
     [with-readonly
      [s/div
       {:style
        {:display :flex
         :align-items :stretch
         :border-bottom [1 :solid (::c/border theme)]}}
       (for [value options]
         ^{:key (->id value)}
         [s/div
          {:style
           {:flex "1"
            :cursor :pointer
            :border-right
            (if (= value (last options))
              :none
              [1 :solid (::c/border theme)])}
           :on-click
           (fn [e]
             (set-option! value)
             (.stopPropagation e))}
          [s/div
           {:style {:box-sizing :border-box
                    :padding (:padding theme)
                    :border-bottom
                    (if (= value option)
                      [5 :solid (::c/boolean theme)]
                      [5 :solid (::c/border theme)])}}
           [preview value]]])]]
     [s/div
      {:style
       {:box-sizing :border-box
        :padding (:padding theme)}}
      [select/with-position
       {:row 0 :column 0}
       [with-key option [inspector (get value option)]]]]]))

(defn- diff-added [value]
  (let [theme (theme/use-theme)
        color (::c/diff-add theme)]
    [s/div
     {:style {:flex 1
              :background (str color "22")
              :border [1 :solid color]
              :border-radius (:border-radius theme)}}
     [inspector value]]))

(defn- diff-removed [value]
  (let [theme (theme/use-theme)
        color (::c/diff-remove theme)]
    [s/div
     {:style {:flex 1
              :background (str color "22")
              :border [1 :solid color]
              :border-radius (:border-radius theme)}}
     [inspector value]]))

(defn- inspect-diff [value]
  (let [theme (theme/use-theme)
        removed (get value :- ::not-found)
        added   (get value :+ ::not-found)]
    [s/div
     {:style {:display :flex :width "100%"}}
     (when-not (= removed ::not-found)
       [s/div {:style
               {:flex 1
                :margin-right
                (when-not (= added ::not-found)
                  (:padding theme))}}
        [diff-removed removed]])
     (when-not (= added ::not-found)
       [s/div {:style {:flex 1}}
        [diff-added added]])]))

(defn- tagged-value [tag value]
  (let [theme (theme/use-theme)
        depth (dec (use-depth))]
    [s/div
     {:style {:display :flex :align-items :center}}
     [s/div
      {:style {:display :flex
               :align-items :center}}
      [s/span {:style {:color (::c/tag theme)}} "#"]
      [with-readonly [inspector tag]]]
     [s/div {:style
             {:flex "1"
              :margin-left (:padding theme)}}
      [with-key
       tag
       [select/with-position {:row 0 :column 0}
        [with-context
         {:depth depth}
         [inspector value]]]]]]))

(defn- preview-coll [open close]
  (fn [value]
    (let [theme (theme/use-theme)]
      [s/div
       {:style
        {:color (::c/diff-remove theme)}}
       open
       (count value)
       close])))

(def ^:private preview-map    (preview-coll "{" "}"))
(def ^:private preview-vector (preview-coll "[" "]"))
(def ^:private preview-list   (preview-coll "(" ")"))
(def ^:private preview-set    (preview-coll "#{" "}"))

(defn- coll-action [props]
  (let [theme (theme/use-theme)]
    [s/div
     {:style {:border-right [1 :solid (::c/border theme)]}}
     [s/div
      {:on-click (:on-click props)
       :style/hover {:color (::c/tag theme)}
       :style {:cursor :pointer
               :user-select :none
               :color (::c/namespace theme)
               :box-sizing :border-box
               :padding (:padding theme)
               :font-size  (:font-size theme)
               :font-family (:font-family theme)}}
      (:title props)]]))

(defn- collection-header [values]
  (let [[show-meta? set-show-meta!] (react/useState false)
        theme    (theme/use-theme)
        metadata (dissoc
                  (meta values)
                  :portal.runtime/id
                  :portal.runtime/type)]
    [s/div
     {:style
      {:border [1 :solid (::c/border theme)]
       :background (get-background)
       :border-top-left-radius (:border-radius theme)
       :border-top-right-radius (:border-radius theme)
       :border-bottom-right-radius 0
       :border-bottom-left-radius 0
       :border-bottom :none}}
     [s/div
      {:style
       {:display :flex
        :align-items :center}}
      [s/div
       {:style
        {:display :inline-block
         :box-sizing :border-box
         :padding (:padding theme)
         :border-right [1 :solid (::c/border theme)]}}
       [preview values]]
      (when (seq metadata)
        [coll-action
         {:on-click
          (fn [e]
            (set-show-meta! not)
            (.stopPropagation e))
          :title "metadata"}])

      (when-let [type (-> values meta :portal.runtime/type)]
        [s/div {:style
                {:box-sizing :border-box
                 :padding (:padding theme)
                 :border-right [1 :solid (::c/border theme)]}}
         [select/with-position {:row 0 :column 0} [inspector type]]])]
     (when show-meta?
       [s/div
        {:style
         {:border-top [1 :solid (::c/border theme)]
          :box-sizing :border-box
          :padding (:padding theme)}}
        [with-depth
         [select/with-position {:row -1 :column 0} [inspector metadata]]]])]))

(defn- container-map-k [child]
  [s/div {:style
          {:grid-column "1"
           :display :flex
           :align-items :flex-start}}
   [s/div {:style
           {:width "100%"
            :top 0
            :position :sticky}}
    child]])

(defn- container-map-v [child]
  [s/div {:style
          {:grid-column "2"
           :display :flex
           :align-items :flex-start}}
   [s/div {:style
           {:width "100%"
            :top 0
            :position :sticky}}
    child]])

(defn try-sort [values]
  (try (sort values)
       (catch :default _e values)))

(defn try-sort-map [values]
  (try (sort-by first values)
       (catch :default _e values)))

(defn- container-map [child]
  (let [theme (theme/use-theme)]
    [s/div
     {:style
      {:width "100%"
       :min-width :fit-content
       :display :grid
       :background (get-background)
       :grid-gap (:padding theme)
       :padding (:padding theme)
       :box-sizing :border-box
       :color (::c/text theme)
       :font-size  (:font-size theme)
       :border-bottom-left-radius (:border-radius theme)
       :border-bottom-right-radius (:border-radius theme)
       :border-top-right-radius 0
       :border-top-left-radius 0
       :border [1 :solid (::c/border theme)]}}
     child]))

(defn inspect-map-k-v [values]
  [container-map
   [l/lazy-seq
    (map-indexed
     (fn [index [k v]]
       ^{:key (str (->id k) (->id v))}
       [:<>
        [select/with-position
         {:row index :column 0}
         [with-context
          {:key? true}
          [container-map-k [inspector k]]]]
        [select/with-position
         {:row index :column 1}
         [with-key k
          [container-map-v [inspector v]]]]])
     (try-sort-map values))]])

(defn- inspect-map [values]
  [with-collection
   values
   [:<>
    [collection-header values]
    [inspect-map-k-v values]]])

(defn- container-coll [values child]
  (let [theme (theme/use-theme)]
    [with-collection
     values
     [s/div
      [collection-header values]
      [s/div
       {:style
        {:width "100%"
         :text-align :left
         :display :grid
         :background (get-background)
         :grid-gap (:padding theme)
         :padding (:padding theme)
         :box-sizing :border-box
         :color (::c/text theme)
         :font-size  (:font-size theme)
         :border-bottom-left-radius (:border-radius theme)
         :border-bottom-right-radius (:border-radius theme)
         :border [1 :solid (::c/border theme)]}}
       child]]]))

(defn- inspect-coll [values]
  [container-coll
   values
   [l/lazy-seq
    (map-indexed
     (fn [index value]
       ^{:key (str index (->id value))}
       [select/with-position
        {:row index :column 0}
        [with-key index [inspector value]]])
     values)]])

(defn- trim-string [string limit]
  (if-not (> (count string) limit)
    string
    (str (subs string 0 limit) "...")))

(defn- inspect-number [value]
  (let [theme (theme/use-theme)]
    [s/span {:style {:color (::c/number theme)}} value]))

(defn- inspect-bigint [value]
  (let [theme (theme/use-theme)]
    [s/span {:style {:color (::c/number theme)}} (str value) "N"]))

(defn hex-color? [string]
  (re-matches #"#[0-9a-fA-F]{6}|#[0-9a-fA-F]{3}gi" string))

(defn rgb-color? [string]
  (re-matches #"rgb\(\d+,\d+,\d+\)" string))

(def color? (some-fn hex-color? rgb-color?))

(defn- url-string? [string]
  (re-matches #"https?://.*" string))

(defn- inspect-string [value]
  (let [theme (theme/use-theme)
        limit (:string-length theme)
        {:keys [expanded?]} @(state/use-state)
        context             (use-context)]
    (cond
      (url-string? value)
      [s/span
       {:style {:color (::c/string theme)}}
       "\""
       [s/a
        {:href value
         :target "_blank"
         :style {:color (::c/string theme)}}
        (trim-string value limit)]
       "\""]

      (color? value)
      [s/div
       {:style
        {:padding (* 0.65 (:padding theme))
         :box-sizing :border-box
         :background value}}
       [s/div
        {:style
         {:text-align :center
          :filter "contrast(500%) saturate(0) invert(1) contrast(500%)"
          :opacity 0.75
          :color value}}
        value]]

      (or (< (count value) limit)
          (= (:depth context) 1)
          (get expanded? (state/get-location context)))
      [s/span {:style {:color (::c/string theme)}}
       (pr-str value)]

      :else
      [s/span {:style {:color (::c/string theme)}}
       (pr-str (trim-string value limit))])))

(defn- inspect-namespace [value]
  (let [theme (theme/use-theme)]
    (when-let [ns (namespace value)]
      [s/span
       [s/span {:style {:color (::c/namespace theme)}} ns]
       [s/span {:style {:color (::c/text theme)}} "/"]])))

(defn- inspect-boolean [value]
  (let [theme (theme/use-theme)]
    [s/span {:style {:color (::c/boolean theme)}}
     (pr-str value)]))

(defn- inspect-symbol [value]
  (let [theme (theme/use-theme)]
    [s/span {:style {:color (::c/symbol theme) :white-space :nowrap}}
     [inspect-namespace value]
     (name value)]))

(defn- inspect-keyword [value]
  (let [theme (theme/use-theme)]
    [s/span {:style {:color (::c/keyword theme) :white-space :nowrap}}
     ":"
     [inspect-namespace value]
     (name value)]))

(defn- inspect-date [value]
  [tagged-value 'inst (.toJSON value)])

(defn- inspect-uuid [value]
  [tagged-value 'uuid (str value)])

(defn- get-var-symbol [value]
  (if (rt/runtime? value)
    (rt/rep value)
    (let [m (meta value)]
      (symbol (name (:ns m)) (name (:name m))))))

(defn- inspect-var [value]
  (let [theme (theme/use-theme)]
    [s/span
     [s/span {:style {:color (::c/tag theme)}} "#'"]
     [inspect-symbol (get-var-symbol value)]]))

(defn- inspect-uri [value]
  (let [theme (theme/use-theme)
        value (str value)]
    [s/a
     {:href value
      :style {:color (::c/uri theme)}
      :target "_blank"}
     value]))

(defn- inspect-tagged [value]
  [tagged-value (:tag value) (:form value)])

(defn- inspect-ansi [string]
  (let [theme (theme/use-theme)]
    (try
      [:pre
       {:style
        {:margin      0
         :font-size   (:font-size theme)
         :font-family (:font-family theme)}
        :dangerouslySetInnerHTML
        {:__html (anser/ansiToHtml string)}}]
      (catch :default e
        (.error js/console e)
        string))))

(defn- inspect-object [value]
  (let [theme  (theme/use-theme)
        string (pr-str value)
        limit  (:string-length theme)
        {:keys [expanded?]} @(state/use-state)
        context             (use-context)]
    [s/span {:style
             {:color (::c/text theme)}}
     [inspect-ansi
      (if (or (< (count string) limit)
              (= (:depth context) 1)
              (get expanded? (state/get-location context)))
        string
        (trim-string string limit))]]))

(defn inspect-long [value]
  [inspect-number (:rep value)])

(defn- get-preview-component [type]
  (case type
    :diff       inspect-diff
    :map        preview-map
    :set        preview-set
    :vector     preview-vector
    :list       preview-list
    :coll       preview-list
    :boolean    inspect-boolean
    :symbol     inspect-symbol
    :number     inspect-number
    :bigint     inspect-bigint
    :string     inspect-string
    :keyword    inspect-keyword
    :date       inspect-date
    :uuid       inspect-uuid
    :var        inspect-var
    :uri        inspect-uri
    :tagged     inspect-tagged
    :error      inspect-error
    "long"      inspect-long
    inspect-object))

(defn preview [value]
  (let [type      (get-value-type value)
        component (get-preview-component type)]
    [component value]))

(defn- get-inspect-component [type]
  (case type
    :diff       inspect-diff
    (:set :vector :list :coll) inspect-coll
    :map        inspect-map
    :boolean    inspect-boolean
    :symbol     inspect-symbol
    :number     inspect-number
    :bigint     inspect-bigint
    :string     inspect-string
    :keyword    inspect-keyword
    :date       inspect-date
    :uuid       inspect-uuid
    :var        inspect-var
    :uri        inspect-uri
    :tagged     inspect-tagged
    :error      inspect-error
    "long"      inspect-long
    inspect-object))

(defn get-info [state context]
  (let [{:keys [search-text expanded?]} @state
        location (state/get-location context)]
    {:selected  (state/selected @state context)
     :expanded? (get expanded? location)
     :viewer    (get-viewer state context)
     :value     (f/filter-value (:value context)
                                (get search-text location ""))}))

(defn inspector [value]
  (let [ref            (react/useRef nil)
        focus-ref      (react/useRef)
        state          (state/use-state)
        context        (-> (use-context)
                           (assoc :value value)
                           (update :depth inc))
        location       (state/get-location context)
        {:keys [value viewer selected expanded?] :as options}
        @(r/track get-info state context)
        theme          (theme/use-theme)
        depth          (use-depth)
        default-expand (and (= (:name viewer) :portal.viewer/inspector)
                            (<= depth (:max-depth theme)))
        expanded?      (if-not (nil? expanded?) expanded? default-expand)
        preview?       (not expanded?)
        type           (get-value-type value)
        component      (or
                        (when-not (= (:name viewer) :portal.viewer/inspector)
                          (:component viewer))
                        (if preview?
                          (get-preview-component type)
                          (get-inspect-component type)))]
    (select/use-register-context context viewer)
    (react/useEffect
     (fn []
       (when selected
         (some-> focus-ref .-current (.focus #js {:preventScroll true}))
         (state/dispatch! state assoc-in [:default-expand location] default-expand)
         #(state/dispatch! state update :default-expand dissoc location)))
     #js [selected])
    (react/useEffect
     (fn []
       (when (and selected
                  (not= (.. js/document -activeElement -tagName) "INPUT"))
         (when-let [el (.-current ref)]
           (when-not (l/element-visible? el)
             (.scrollIntoView el #js {:inline "nearest" :behavior "smooth"})))))
     #js [selected (.-current ref)])
    [with-context
     context
     (when-not (:readonly? context)
       [s/div
        {:ref         focus-ref
         :tab-index   0
         :style/focus {:outline :none}
         :style       {:position :absolute}
         :on-focus
         (fn [e]
           (when-not selected
             (state/dispatch! state state/select-context context false)
             (.stopPropagation e)))}])
     [s/div
      (merge
       (when-not (:readonly? context)
         {:on-mouse-down
          (fn [e]
            (.stopPropagation e)
            (when (= (.-button e) 1)
              (state/dispatch! state state/toggle-expand location)
              (.stopPropagation e)))
          :on-click
          (fn [e]
            (state/dispatch!
             state
             (if selected
               state/deselect-context
               state/select-context)
             context
             (or (.-metaKey e) (.-altKey e)))
            (.stopPropagation e))
          :on-double-click
          (fn [e]
            (state/dispatch! state state/nav context)
            (.stopPropagation e))})
       {:ref   ref
        :title (-> value meta :doc)
        :style
        {:width         "100%"
         :border-radius (:border-radius theme)
         :border        (if selected
                          [1 :solid (get theme (nth theme/order selected))]
                          [1 :solid "rgba(0,0,0,0)"])
         :box-shadow    (when selected [0 0 3 (get theme (nth theme/order selected))])
         :background    (let [bg (get-background (inc depth))]
                          (when selected bg))}})
      [:> error-boundary
       [with-options options [component value]]]]]))

(def viewer
  {:predicate (constantly true)
   :component inspector
   :name :portal.viewer/inspector})
