(ns atp-stats-api.core
  (:require [cheshire.core :refer [parse-string]]
            [net.cgrand.enlive-html :as html]
            [clojure.string :as string]))

;; CONSTANTS

(def ^:const base-url "http://www.atpworldtour.com")
(def ^:const url-map {:predictive-tournament-search "/-/ajax/PredictiveContentSearch/GetTournamentResults/"
                      :predictive-player-search     "/-/ajax/PredictiveContentSearch/GetPlayerResults/"
                      :player-id-search             "/-/ajax/playersearch/PlayerIdSearch?SearchTerm="
                      :player-name-search           "/-/ajax/playersearch/PlayerNameSearch?SearchTerm="
                      :initial-scores               "/-/ajax/scores/getinitialscores/"
                      :tournament-archive           "/-/ajax/Scores/GetTournamentArchiveForYear/"
                      :players                      "/players/"
                      :scores                       "/scores/archive/"
                      :tournaments                  "/tournaments/"})

;; HELPER FUNCTIONS

(def filter-first (comp first filter))

(defn create-atp-url [k & args]
  (apply str base-url (get url-map k) args))

(defn ajax-query [url-kw & [args]]
  (->> args
       (create-atp-url url-kw)
       slurp
       parse-string))

(defn fetch-url [url]
  (html/html-resource (java.net.URL. url)))

;; Since it seems like multi-set data does not exist, only show set "0", i.e. total match data.
;; Unless, of course, we miraculalously get decent tennis data and it exists, in which case
;; you can show all of the set data
(defn multi-sets-filter [stats]
  (if (get "playerStats" (filter-first #(= 1 (get % "setNum")) stats))
    stats
    (first stats)))

(defn clean-breaks [s]
  (string/replace s #"[\t\n]" ""))

(defn single-text-element [page selector nth-fn]
  (-> page
      (html/select selector)
      nth-fn
      :content
      first
      clean-breaks))

(defn to-int [s]
  (try (Integer/parseInt s)
       (catch NumberFormatException e nil)))

(defn measurement-conversion [page selector regex]
  (-> page
      (single-text-element selector first)
      (string/replace regex "")
      to-int))

;; API

; JSON AJAX routes
(defn tournament-search [q]
  (ajax-query :predictive-tournament-search q))

(defn player-search [q]
  (ajax-query :predictive-player-search q))

(defn player-id-search [q]
  (ajax-query :player-id-search q))

(defn player-name-search [q]
  (ajax-query :player-name-search q))

(defn initial-scores []
  (ajax-query :initial-scores))

(defn tournament-archive [q]
  (ajax-query :tournament-archive q))

; HTML pages (boo)
(defn match-stats [{:keys [tournament tournament-id year p1 p2] :as params}]
  (let [url (create-atp-url :tournaments tournament "/" tournament-id "/" year "/match-stats/" p1 "/" p2 "/match-stats?ajax=true")]
    (merge params
           {:data (-> url
                      fetch-url
                      (html/select [:script#matchStatsData])
                      first
                      :content
                      first
                      parse-string
                      multi-sets-filter)
            :url  url})))

(defn tournament-stats [{:keys [tournament tournament-id year] :as params}]
  (let [url (create-atp-url :scores tournament "/" tournament-id "/" year "/results")
        page (fetch-url url)]
    (merge params
           {:matches (map #(get-in % [:attrs :href]) (html/select page [:td.day-table-score :a]))
            :url     url
            :draw    {:singles (single-text-element page [:span.item-value] first)
                      :doubles (single-text-element page [:span.item-value] second)}
            :court   (single-text-element page [:span.item-value] #(nth % 2))
            :prize   (single-text-element page [:td.prize-money :div.info-area :div.item-details :span.item-value] first)
            :dates   (single-text-element page [:span.tourney-dates] first)})))

(defn player-stats [{:keys [player-url player-id] :as params}]
  (let [url (create-atp-url :players player-url "/" player-id "/player-activity?year=all&ajax=true")
        page (fetch-url url)
        plays (single-text-element page [[:div.table-value (html/pred #(.contains (html/text %) "Handed"))]] first)]
    (merge params
           {:url            url
            :first-name     (single-text-element page [:div.first-name] first)
            :last-name      (single-text-element page [:div.last-name] first)
            :birthday       (string/replace (single-text-element page [:span.table-birthday] first) #"\(\)" "")
            :height         (measurement-conversion page [:span.table-height-cm-wrapper] #"[\(\)cm]")
            :weight         (measurement-conversion page [:span.table-weight-kg-wrapper] #"[\(\)kg]")
            :plays          plays
            :left-handed?   (boolean (re-find #"Left-Handed" plays))
            :one-handed-bh? (boolean (re-find #"One-Handed" plays))})))