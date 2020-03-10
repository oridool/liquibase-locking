# Liquibase-locking

This project is enhancing Liquibase built-in DB locking mechanism, to avoid infinite locking.

### The problem with Liquibase built-in locking
When an application is using Liquibase for DB upgrade and maintenance, it usually runs Liquibase as part of startup.  
Liquibase uses a locking table (default is `databasechangeloglock`) to indicate if the upgrade process is still in progress.
Specifically, this table contains the `LOCKED` and `LOCKEDBY` columns.  

When Liquibase starts upgrade, it is populating the locking table and sets `LOCKED = true`.  
Thus, other application instances are started at the same time they wait each other. Eventually, only a single service instance is performing upgrade steps at a given time.    

This mechanism is problematic if the application instance is stopped (crashed or brutally forced to stop) during upgrade time. The database and locking table remains in a 'locked' state, and any new application instance that tries to start would wait infinitely until database is fixed and lock is manually released.  
There is no way to automatically recover from this state.  

The problem becomes more severe in the world of microservices and Kubernetes, where multiple instances per service (PODs) are executed, and those instances are controlled by K8S. K8S can shut down services unexpectedly, even during startup. We also expect full automation and auto-recovery at any state.   
  
### Solution in this project
This project implements some Java classes that override the default Liquibase locking mechanism.
When upgrade starts, Liquibase will now utilizes the `LOCKEDBY` property with the value of the current DB session properties. This is a globally unique identifier, that only valid for the current session.    
When any other application instance is starting, it first checks if the DB session that is currently stored in `LOCKEDBY` is alive.  
If session is not alive, it means that the original client that locked the table is dead and the lock can be released. The new application instance will take ownership of the lock (using its own session id).
If session is still alive, application waits for the lock to be released by the lock owner (this is default Liquibase behavior).  

The solution is currently written for **PostgreSQL** database, but can be easily enhanced to any other database type.  
In PostgreSQL, the sessions are maintained in the table `pg_stat_activity`.
The `databasechangeloglock.LOCKEDBY` column is populated with both the session *PID* and its *Created Timestamp* to guarentee uniqueness.

Example for `LOCKEDBY` value (`@@` is a pre-configured separator): 
```
29800@@2020-03-09 10:38:12.95154
``` 
      

### Usage:
Include the generated jar in your project dependencies, it will override Liquibase default locking.

### Credits:
Inspired by similar project - https://github.com/szintezisnet/Kubernetes-Liquibase-Lock-Release  

However, I think that the K8S approach can be an overkill in most cases. And will not work if you are running outside Kubernetes.
By taking the direct database approach, the solution can be applied to all types of running environments.
