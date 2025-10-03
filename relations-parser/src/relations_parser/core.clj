(ns relations-parser.core
  (:require
   [clojure.string :as str]
   [org-parser.core :as org]
   [clojure.java.io :as io]))

;; --- санитизация входного текста ---

(def ^:private weird-chars
  [\uFEFF      ;; BOM
   \u00A0      ;; NBSP
   \u200B      ;; ZERO WIDTH SPACE
   \u200C      ;; ZERO WIDTH NON-JOINER
   \u200D      ;; ZERO WIDTH JOINER
   \u2028      ;; LINE SEPARATOR
   \u2029])    ;; PARAGRAPH SEPARATOR

(defn- sanitize-text [s]
  (-> s
      (str/replace #"\r\n?" "\n")                 ;; CRLF/CR -> LF
      (str/replace (re-pattern (apply str weird-chars)) "")  ;; выкинуть странные
      (str/replace #"[ \t]+\n" "\n")))            ;; обрезать хвостовые пробелы

(defn- read-org-safe [path]
  (let [raw (slurp path :encoding "UTF-8")]
    (try
      (org/read-str raw)
      (catch Throwable _
        ;; повторная попытка на санитезированном тексте
        (org/read-str (sanitize-text raw))))))
;; ---------- настройка секций блока RELATIONS ----------

(def ^:private section->etype
  {"leads to"  :leads-to
   "requires"  :requires
   "subset of" :subset-of
   "siblings"  :siblings})

;; ---------- утилиты ----------

(defn- norm-title
  "Склеивает заголовок Org из вектора токенов в строку и нормализует."
  [title-tokens]
  (->> title-tokens
       (map (fn [tok]
              (cond
                (vector? tok)
                (let [[tag s] tok]
                  (if (or (= tag :text-normal)
                          (= tag :text-sty-bold)
                          (= tag :text-sty-italic)
                          (= tag :text-sty-underl))
                    (second tok)
                    ""))

                (string? tok) tok
                :else "")))
       (apply str)
       str/trim
       str/lower-case))

(defn- headline->etype [h]
  (some-> h :headline :title norm-title section->etype))

(defn- vecwalk
  "Глубокий проход по векторно-дереву токенов, вызывая f на каждом узле."
  [node f]
  (when node
    (f node)
    (when (sequential? node)
      (doseq [x node] (vecwalk x f)))))

(defn- ast-find
  "Возвращает true, если предикат p срабатывает на каком-то узле в AST-векторе."
  [ast p]
  (let [hit? (volatile! false)]
    (vecwalk ast #(when (and (vector? %) (p %)) (vreset! hit? true)))
    @hit?))

(defn- ast-links
  "Извлекает все [[id:...][label]] из section/:ast заголовка."
  [section-ast]
  (let [links (volatile! [])]
    (vecwalk section-ast
             (fn [node]
               (when (and (vector? node) (= (first node) :link-format))
                 ;; ожидаем структуру вида:
                 ;; [:link-format [:link [:link-ext [:link-ext-id \"UUID\"]]] [:link-description \"desc\"]]
                 (let [id   (atom nil)
                       desc (atom nil)]
                   (vecwalk node
                            (fn [n]
                              (when (and (vector? n)
                                         (= (first n) :link-ext-id))
                                (reset! id (second n)))
                              (when (and (vector? n)
                                         (= (first n) :link-description))
                                (reset! desc (second n)))))
                   (when @id
                     (vswap! links conj {:to @id :label @desc}))))))
    @links))

;; ---------- извлечение свойств документа ----------

(defn- preamble-props
  "Достаёт drawer PROPERTIES из preamble как {:ID .., :WHAT .., ...}."
  [preamble]
  (let [lines (get-in preamble [:section :raw])]
    (->> (when lines
           (->> lines
                (drop-while #(not= % ":PROPERTIES:"))
                (rest)
                (take-while #(not= % ":END:"))))
         (keep (fn [s]
                 (when (str/starts-with? s ":")
                   (let [[_ k & vs] (str/split s #":\s*")
                         k* (when k (-> k str/upper-case keyword))
                         v* (-> (str/join ":" vs) str/trim)]
                     (when (and k* (not= (name k*) "PROPERTIES"))
                       [k* v*])))))
         (into {}))))


;; ---------- выделение title ----------

(defn- extract-title
  "Находит #+title: в преамбуле и извлекает его значение."
  [preamble]
  (let [lines (get-in preamble [:section :raw])]
    (some (fn [line]
            (when (str/starts-with? line "#+title:")
              (-> line
                  (str/replace #"#\+title:\s*" "") ; Убираем '#+title: '
                  str/trim))) ; Чистим от лишних пробелов
          lines)))

;; ---------- выделение блока RELATIONS ----------

(defn- relations-present? [preamble-ast]
  (ast-find preamble-ast
            (fn [node] (and (= (first node) :block-begin-line)
                            (= (second node) [:block-name "RELATIONS"])))))

(defn- block-end-in-headline? [h]
  (ast-find (get-in h [:section :ast])
            (fn [node] (and (= (first node) :block-end-line)
                            (= (second node) [:block-name "RELATIONS"])))))

(defn- pick-relations-headlines
  "Возвращает последовательность заголовков, относящихся к первому блоку RELATIONS.
   Эвристика соответствует наблюдаемому выводу org-parser: начало блока в preamble,
   конец — токен :block-end-line в последнем headline секции."
  [{:keys [preamble headlines]}]
  (when (and preamble (relations-present? (get-in preamble [:section :ast])))
    (if (seq headlines)
      (let [[taken _]
            (reduce (fn [[acc done?] h]
                      (if done?
                        [acc true]
                        (let [acc2 (conj acc h)
                              end? (block-end-in-headline? h)]
                          [acc2 end?])))
                    [[] false]
                    headlines)]
        taken)
      [])))

;; ---------- публичный API ----------


(defn parse-file
  "Читает .org → {:doc/id ... :properties ... :edges ...}"
  [fpath]
  (let [ast     (read-org-safe fpath)
        pre     (:preamble ast) ; Вынесем преамбулу в отдельную переменную
        props   (preamble-props pre)
        title   (extract-title pre) ; <-- ВЫЗЫВАЕМ НОВУЮ ФУНКЦИЮ
        this-id (:ID props)
        rel-hs  (pick-relations-headlines ast)]
    {:doc/id this-id
     :title title
     :properties props
     :edges (->> rel-hs
                 (mapcat (fn [h]
                           (when-let [etype (headline->etype h)]
                             (let [links (ast-links (get-in h [:section :ast]))]
                               (for [{:keys [to label]} links :when to]
                                 {:edge/type etype
                                  :from this-id
                                  :to   to
                                  :label label})))))
                 vec)}))

(defn parse-dir
  "Собирает {:doc/id ... :properties ... :edges ...} по всем .org в каталоге (без рекурсии)."
  [dir]
  (->> (file-seq (clojure.java.io/file dir))
       (filter #(and (.isFile ^java.io.File %)
                     (str/ends-with? (.getName ^java.io.File %) ".org")
                     (= (.getParent ^java.io.File %) (-> dir clojure.java.io/file .getPath))))
       (map #(parse-file (.getPath ^java.io.File %)))
       vec))

(defn -main [& [dir]]
  (let [dir (or dir ".")]
    (println (pr-str (parse-dir dir)))))
