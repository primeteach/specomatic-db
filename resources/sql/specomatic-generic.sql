-- :name alter-table :!
-- :doc alter table
alter table :i:table :sql:column-defs

-- :name delete-reference-coll-element! :! :n
/* :require [clojure.string :as string]
            [hugsql.parameters :refer [identifier-param-quote]] */
delete from :i:table
where
:i:target-idfield
= :target-id
and
:i:entity-idfield
= :entity-id
