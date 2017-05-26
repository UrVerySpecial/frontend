(ns frontend.parser.connection)

(defn- forgiving-subvec
  "Like subvec, but doesn't throw if you ask for elements beyond the end of the vector.

  (forgiving-subvec [:a :b] 1 5) ;=> [:b]
  (forgiving-subvec [:a :b] 4 5) ;=> []"
  [v offset limit]
  (subvec v
          (min (count v) offset)
          (min (count v) (+ offset limit))))

(defn read [next-read]
  (fn [{:keys [target read-path] {{:keys [connection/offset connection/limit] :as params} :params :keys [key]} :ast :as env}]
    (if (nil? target)
      (if (or offset limit)
        (do
          (assert
           (and offset limit)
           (str ":connection/offset and :connection/limit must be given together."))
          (let [new-env (-> env
                            (update-in [:ast :params] dissoc :connection/offset :connection/limit)
                            (update :state
                                    ;; env contains the state atom, not a
                                    ;; persistent state map. We want to annotate
                                    ;; the state map with the offset and limit to
                                    ;; allow the downstream read mechanism to read
                                    ;; that if it was queried for. To do that, we
                                    ;; have to deref the state atom, modify it,
                                    ;; and re-wrap it in an atom. This
                                    ;; demonstrates that Bodhi should pass the
                                    ;; persistent state map through the read
                                    ;; stack, not the atom.
                                    #(atom (update-in @% read-path assoc
                                                      :connection/offset offset
                                                      :connection/limit limit))))
                result (next-read new-env)]
            (-> (update-in result [:value :connection/edges] forgiving-subvec offset limit))))
        (next-read env))

      ;; Until the backend supports offset and limit, strip these.
      (if (#{:connection/offset :connection/limit} key)
        {target nil}
        (next-read (-> env
                       (update-in [:ast :params] dissoc :connection/offset :connection/limit)
                       (update :ast #(if (empty? (:params %))
                                       (dissoc % :params)
                                       %))))))))
