;;; template.clj -- A templating system for embedding Clojure in documents.

;; by Jeff Rose <rosejn@gmail.com>
;; Dec 1, 2008

;; Copyright (c) 2008 Jeff Rose. All rights reserved.  The use and
;; distribution terms for this software are covered by the Common
;; Public License 1.0 (http://www.opensource.org/licenses/cpl1.0.php)
;; which can be found in the file CPL.TXT at the root of this
;; distribution.  By using this software in any fashion, you are
;; agreeing to be bound by the terms of this license.  You must not
;; remove this notice, or any other, from this software.

;; This library defines a template function that given a string generates a
;; new function which when called instantiates the template and outputs a
;; string with the embedded code evaluated under the current bindings.

(ns template
  (:import (java.util Scanner)))

(defn- scanner
  "Returns a hash of functions closing over a java Scanner object.
  scan: returns the next regexp match, and moves over the input stream
  more?: a test to see whether more data is available"
        [input]
        (let [scanr (new Scanner input)]
    {:scan (fn [pattern]
             (if (nil? (.findInLine scanr pattern))
               [(.nextLine scanr) "\n"]
               (let [[whole text delim] (re-groups (.match scanr))]
                 [text delim])))
     :more? (fn [] (.hasNextLine scanr))
     }))

(defn- print-form
  "Returns an updated code string with an appended print form."
        [code string]
        (if (empty? string)
    code
    (str code "\n(print \"" string "\")")))

(defn- eval-form
  "Returns an updated code string with an appended lisp form."
        [code form-string]
  (if (empty? form-string)
    code
    (str code "\n" form-string)))

(defn- eval-print-form
  "Returns an updated code string with an appended form that
  prints the output of a lisp form."
        [code form-string]
  (if (empty? form-string)
    code
    (str code "\n(print " form-string ")")))

(defn- handle-trim
  "Returns an updated code string with an appended print form
  and a newline, or lack thereof depending on the closing
  tag."
        [code buffer text last-tag]
  (print-form code
              (if (= last-tag "-%>")
                (str buffer text)
                (str buffer text "\\n"))))

(defn- generate-template
  "Generates a clojure program that represents a template with a set of embedded
  evaluations.  By eval'ing this form under different bindings you can
  populate the template with different values."
        [template]
  (let [scn (scanner template)
        re-start #"(.*?)(<\?clj|<\?clj=|<\?clj;|<%=|<%;|<%)" ; Start tokens
        re-end #"(.*?)(%>|-%>|\?>|-\?>)"]      ; End tokens
    (loop [code ""
           buffer ""
           cur-tag nil
           last-tag nil]
      (if ((:more? scn))
        (if cur-tag

          ; We are inside a set of script tags, so either eval some code,
          ; insert the output of some code, or do nothing in a comment.
          (let [[text delim] ((:scan scn) re-end)]
            (case cur-tag
                  ; eval but don't insert
                  "<%"  (recur (eval-form code text) "" nil delim)
                  ; eval and insert
                  "<%=" (recur (eval-print-form code text) "" nil delim)
                  "<?clj" (recur (eval-print-form code text) "" nil delim)
                  ; skip commented forms
                  "<%;" (recur code buffer nil delim)))

          ; We are scanning through content looking for an opening tag
          ; insert print forms for normal content
          (let [[text delim] ((:scan scn) re-start)]
            (condp re-seq delim        ; We are in regular content
                     #"(<?clj|^%|<%|<%=|<%;)" (recur (print-form code (str buffer text)) "" delim nil)
                     #"\n"           (recur (handle-trim code buffer text last-tag) "" cur-tag nil))))

        ; Return a do-wrapped set of forms
        (str "(do " code ")")))))

(defn template
  "Returns a function representing a compiled template.  The function
  returns a string, and by calling this function under different bindings
  the same template can be populated with different values."
        [tpl]
  (let [tpl-code (generate-template tpl)
        tpl-forms (with-in-str tpl-code (read))]
    (fn [] (with-out-str (eval tpl-forms)))))

;; Testing and examples follow
(comment
(def bar (ref 1))
(defn ^:dynamic foo [a] (str a @bar))
(def ^:dynamic *animals* "elephants and giraffes")

(def ^:dynamic *example-template*
"This is a test of <%= (- 10 9) %> + <%= (+ -10 11) %> animals: <%= *animals* %>, to
see if it gets<% (dosync (ref-set bar (inc @bar)))%> <%= (foo \"us \") %> more friends.
Lets try to print <%; commented out %>explicitly: <% (doseq [i [1 2 3 4]] (print i)) %>
And now lets try iterating over a sequence inside a template:
<ul>
<% (doseq [animal [\"horses\" \"donkeys\"]] -%>
  <li><%= animal %></li>
<% ) -%>
</ul>
And that's all for now.\n")

(def ^:dynamic *expected-output*
"This is a test of 1 + 1 animals: elephants and giraffes, to
see if it gets us 2 more friends.
Lets try to print explicitly: 1234
And now lets try iterating over a sequence inside a template:
<ul>
  <li>horses</li>
  <li>donkeys</li>
</ul>
And that's all for now.\n")

(defn test-template []
  (let [tmpl-fn (template *example-template*)]
    (doseq [line ["Template:\n" *example-template*
                  "Code:\n-------------------------------"
                  (generate-template *example-template*)
                  "\nExpected:\n-------------------------------"
                  *expected-output*
                  "\nresult:\n-------------------------------"
                  (tmpl-fn)
                  "Output matches correctly:\n-------------------------------"
                  (= *expected-output* (tmpl-fn))
                  "\nIn a binding:\n-------------------------------"
                  (binding [*animals* "dogs and cats"
                            foo (fn [a] (str "different stuff" a))]
                    (tmpl-fn))]]
      (println line))))

(test-template))
