Database creation instructions for Claudio Nieder
=

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