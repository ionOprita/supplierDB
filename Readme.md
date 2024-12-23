Emag -> Google Drive
=

Start Update Feedback Clients Sheets Application
-

To start the application, run the class from this path:
```
src/main/java/ro/sellfluence/app/UpdateAllSheetsMain.java
```

Emag Mirror to DB
=

Database setup
-
These instructions work for the development environment of claudio. Maybe the need to be modified for other environments.

```
psql template1 -X -c "CREATE DATABASE emag WITH OWNER = $(id -nu)"
psql -1 -X -c "CREATE ROLE emag WITH LOGIN PASSWORD 'password'; GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO emag"
```

Substitute *password* with the real password.

Then add the following line to $HOME/Secrets/dbpass.txt again putting the password instead of the word *password*.

```
emag    jdbc:postgresql://127.0.0.1:5432/emag       emag        password
```

Make sure each field is separated by a single TAB character.

To quickly drop all tables for testing, execute this:

```
psql -t -d emag -c "SELECT 'DROP TABLE IF EXISTS ' || tablename || ' CASCADE;' FROM pg_tables WHERE schemaname = 'public';" | psql -d emag
```
for the remote database use

```
psql -t -h 86.124.84.214 -U emag -d emag -c "SELECT 'DROP TABLE IF EXISTS ' || tablename || ' CASCADE;' FROM pg_tables WHERE schemaname = 'public';" | psql -h 86.124.84.214 -U emag -d emag
```

To see if there are missing dates, this SQL statement can be used:

```
WITH ranked_orders AS (
    SELECT 
        emag_login, 
        order_start, 
        order_end, 
        LEAD(order_start) OVER (PARTITION BY emag_login ORDER BY order_start) AS next_order_start
    FROM emag_fetch_log
)
SELECT 
    emag_login, 
    order_end AS gap_start, 
    next_order_start AS gap_end
FROM 
    ranked_orders
WHERE 
    order_end < next_order_start;
```

Here an explanation what the LEAD... does:

The LEAD function in SQL is a window function that allows you to access data from subsequent rows in your result set. It can be particularly useful for comparing values in a sequence, like detecting gaps in time ranges.
Here's a breakdown of the part "LEAD(order_start) OVER (PARTITION BY emag_login ORDER BY order_start) AS next_order_start":

- LEAD(order_start): This part of the function tells SQL to look at the order_start value in the next row. Essentially, it retrieves the order_start value of the subsequent record.
- OVER (PARTITION BY emag_login ORDER BY order_start): This clause is critical because it defines how the LEAD function should be applied:
  - PARTITION BY emag_login: This partitions the data by emag_login, meaning the function is applied separately to each emag_login group. It ensures that comparisons are only made within the same emag_login.
  - ORDER BY order_start: Within each partition, the data is ordered by order_start. This ordering is necessary for the LEAD function to correctly identify the next order_start for comparison.
- AS next_order_start: This gives the result of the LEAD function a name, next_order_start, which you can then reference in your main query.

Putting it all together, the LEAD(order_start) OVER (PARTITION BY emag_login ORDER BY order_start) AS next_order_start part of the query calculates the order_start value for the next row in the same emag_login group. This calculated value is then used to detect if there are any gaps in the order_start and order_end times for each emag_login.

Stuff related to scraping app
=

API-Key
-

The scrpingant api-key needs to be configured in the application.properties by
adding a line like this:

```
ro.koppel.apiKey=ff7320bfe38e2b076fd0a7aa42a4abbc
```
The value behind the = sign needs to be the api-key from the scraping agent website.

Command line arguments
-
Exactly two arguments are required.

The first argument is an absolute or relative
path to the text file containing the search terms. Each line is an individual search
and can contain one or several words separated by a blank.

The second argument is an absolute or relative path for the Excel file to be created
or modified (TODO).

Database creation instructions
-

As user 'claudio' is already defined as superuser instead of postgres
and cannot be used with a password, a different user needed to be created.

Here are the instructions to create user (e.g. supplierdb) and database (e.g. supplierdb), and these instructions can
be used similarly on other systems to create a specific user for this application
and a database owned by this user. The password should normally be something less
guessable than in the following example (again supplierdb).

```shell
psql template1 -c "CREATE ROLE supplierdb WITH LOGIN PASSWORD 'supplierdb'" 
psql template1 -c "CREATE DATABASE supplierdb WITH OWNER = supplierdb" 
```

It is a bit untypical to make the user also owner of the database, but as it 
is used with hibernate and will create the tables itself, it needs to be owner.
This is unlike a traditional scenario, where the tables would be created
by the database owner and the application would use a less privileged user
that has only the rights to modify the table's content.

Additionally, the pg_hba.conf file needs to have the following entry

```
host supplierdb supplierdb localhost scram-sha-256
```
Alternatively, the pg_hab.conf file could have this entry which gives access
to all users but only to a database which is named like the user, e.g. user
supplierdb can access only database supplierdb.

```
host sameuser all localhost scram-sha-256
```

After performing these steps, the application.properties file needs to have these
values:
```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/supplierdb
spring.datasource.username=supplierdb
spring.datasource.password=supplierdb
```

