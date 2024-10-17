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

