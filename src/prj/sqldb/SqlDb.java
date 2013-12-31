package prj.sqldb;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;
import prj.sqldb.threading.Later;
import prj.sqldb.threading.SqlDBThreads;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;

public class SqlDb
{
    /*
    Sqldb provides an async api to execute queries on the android SQLite
    database.
    Uses one thread for writing to DB as sqlite only supports one writer.
    Uses one thread for reading from DB. This is sufficient for most usecases
     by a margin.

    All methods return a future instead of 'void' in order to allow the
    application thread to wait till
    there is a result, if the application so desires.

    TODO: offer support for multiple readers from db.
     */

    private final SQLiteDatabase _db; //Underlying sqlite database

    //An executor which provides thread on which results from queries will be
    // returned
    private final ExecutorService _appExecutor;

    public SqlDb(SQLiteOpenHelper helper, ExecutorService appExecutor,
                 boolean disableSync)
    {
        _db = helper.getWritableDatabase(); //Writable database handles both
        // reads and writes

        if (disableSync)
        {
            /*
            Sync pragma in sqlite forces each write to be commited to disk
            before returning, this can
            be very slow and is not typically required by most applications.
            Setting disable sync to 'true'
            disables this and speeds up dbwrites significantly.
            More information -http://www.sqlite.org/pragma
            .html#pragma_synchronous
            */
            _db.execSQL("PRAGMA synchronous=0");
        }
        if (Build.VERSION.SDK_INT >= 11)
        {
            /*
            Write ahead logging is available from android api 11 and higher
            and significantly speeds up database
            operations.
            More info - http://www.sqlite.org/draft/wal.html
             */
            _db.enableWriteAheadLogging();
        }
        _appExecutor = appExecutor;
    }

    public interface ITransactionCompleteCallback
    {
        /*
        A simple callback which is required by the the runInTransaction
        method to inform the app that the
        transaction has completed
        */
        void onComplete(boolean b);
    }

    /*
    Query methods: These methods provide access to a cursor via the
    CursorHandler. The execution of the cursor is done
    by calling the CursorHandler.handle method in the db reader thread. The
    result of the CursorHandler.handle method
    is then made available to the app in the app provided executor thread via
     the CursorHandler.callback method -
    this ensures that the app does not keep the reader thread busy and that
    it becomes available for other read operations.

    All these methods return a Future, instead of a void,
    and this future can be used by the calling thread to wait till
    a result is available. This provides, some sort of synchronous support in
     this otherwise async library.
     */

    public <RESULT> Future<RESULT> rawQuery(final String sql,
                                            final String[] selectionArgs,
                                            final CursorHandler<RESULT> handler)
    {
        final Later<RESULT> l = new Later<RESULT>();
        ScheduledFuture<?> f = SqlDBThreads.scheduleOnReaderDBExecutor(new Runnable()
        {
            @Override
            public void run()
            {
                final Cursor cursor = _db.rawQuery(sql, selectionArgs);
                final RESULT result = handler.handle(cursor);
                l.set(result);
                _appExecutor.submit(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        handler.callback(result);
                    }
                });
            }
        });
        l.wrap(f);
        return l;
    }

    public <RESULT> Future<RESULT> query(final String table,
                                         final String[] columns,
                                         final String selection,
                                         final String[] selectionArgs,
                                         final String groupBy,
                                         final String having,
                                         final String orderBy,
                                         final String limit,
                                         final CursorHandler<RESULT> handler)
    {
        final Later<RESULT> l = new Later<RESULT>();
        Runnable r = new Runnable()
        {
            @Override
            public void run()
            {
                Cursor c = _db.query(table, columns, selection, selectionArgs,
                        groupBy, having, orderBy, limit);
                final RESULT result = handler.handle(c);
                l.set(result);
                Runnable rr = new Runnable()
                {
                    @Override
                    public void run()
                    {
                        handler.callback(result);
                    }
                };
                _appExecutor.submit(rr);
            }
        };
        ScheduledFuture<?> f = SqlDBThreads.scheduleOnReaderDBExecutor(r);
        l.wrap(f);
        return l;
    }

    public <RESULT> Future<RESULT> query(final String table,
                                         final String[] columns,
                                         final String selection,
                                         final String[] selectionArgs,
                                         final String groupBy,
                                         final String having,
                                         final String orderBy,
                                         final CursorHandler<RESULT> handler)
    {
        return query(table, columns, selection, selectionArgs, groupBy,
                having, orderBy, null /*limit*/, handler);
    }

    public <RESULT> Future<RESULT> query(final String table,
                                         final String[] columns,
                                         final String selection,
                                         final String[] selectionArgs)
    {
        return query(table, columns, selection, selectionArgs, null, null,
                null, null);
    }

    /*
    Modification methods: These methods execute on a single thread dedicated
    for DB writes. They return the
    number of rows effected via a DBCallback.

    All these methods return a Future, instead of a void,
    and this future can be used by the calling thread to wait till
    a result is available. This provides, some sort of synchronous support in
     this otherwise async library.
     */

    public Future<Integer> delete(final String table,
                                  final String whereClause,
                                  final String[] whereArgs, final DBCallback cb)
    {
        final Later<Integer> l = new Later<Integer>();
        Runnable r = new Runnable()
        {
            @Override
            public void run()
            {
                int numRows = _db.delete(table, whereClause, whereArgs);
                l.set(numRows);
                executeCallbackInAppExecutor(cb, numRows);

            }
        };
        ScheduledFuture<?> f = SqlDBThreads.scheduleOnWriterDBExecutor(r);
        l.wrap(f);
        return l;
    }

    public Future<Long> insertWithOnConflict(final String table,
                                             final String nullColumnHack,
                                             final ContentValues initialValues,
                                             final int conflictAlgorithm,
                                             final DBCallback cb)
    {
        final Later<Long> l = new Later<Long>();
        Runnable r = new Runnable()
        {
            @Override
            public void run()
            {
                long id = _db.insertWithOnConflict(table, nullColumnHack,
                        initialValues, conflictAlgorithm);
                l.set(id);
                executeCallbackInAppExecutor(cb, id);
            }
        };
        ScheduledFuture<?> f = SqlDBThreads.scheduleOnWriterDBExecutor(r);
        l.wrap(f);
        return l;
    }

    public Future<Long> insert(String table, String nullColumnHack,
                               ContentValues values, DBCallback cb)
    {
        return insertWithOnConflict(table, nullColumnHack, values,
                SQLiteDatabase.CONFLICT_NONE, cb);
    }

    public Future<Integer> updateWithOnConflict(final String table,
                                                final ContentValues values,
                                                final String whereClause,
                                                final String[] whereArgs,
                                                final int conflictAlgorithm,
                                                final DBCallback cb)
    {
        final Later<Integer> l = new Later<Integer>();
        Runnable r = new Runnable()
        {
            @Override
            public void run()
            {
                int numRows = _db.updateWithOnConflict(table, values,
                        whereClause, whereArgs, conflictAlgorithm);
                l.set(numRows);
                executeCallbackInAppExecutor(cb, numRows);
            }
        };
        ScheduledFuture<?> f = SqlDBThreads.scheduleOnWriterDBExecutor(r);
        l.wrap(f);
        return l;
    }

    public Future<Integer> update(String table, ContentValues values,
                                  String whereClause, String[] whereArgs,
                                  DBCallback cb)
    {
        return updateWithOnConflict(table, values, whereClause, whereArgs,
                SQLiteDatabase.CONFLICT_NONE, cb);
    }

    public Future<Long> replace(final String table,
                                final String nullColumnHack,
                                final ContentValues initialValues,
                                final DBCallback cb)
    {
        final Later<Long> l = new Later<Long>();
        Runnable r = new Runnable()
        {
            @Override
            public void run()
            {
                long id = _db.replace(table, nullColumnHack, initialValues);
                l.set(id);
                executeCallbackInAppExecutor(cb, id);
            }
        };
        ScheduledFuture<?> f = SqlDBThreads.scheduleOnWriterDBExecutor(r);
        l.wrap(f);
        return l;
    }


    public Later<Boolean> runInTransaction(final Runnable job,
                                           final ITransactionCompleteCallback
                                                   callback)
    {
        /*
         This method executes a runnable inside a transaction and fires a
        callback when the operation is finished.

        WARNING: This method will DEADLOCK if the runnable blocks by using the
        futures that are returned from the methods in this class. To use this
        method properly don't use Future.get inside the runnable. Having
        said that there is no use case that can possibly benefit from
        blocking on the future inside the runnable.
          */

        final Later<Boolean> l = new Later<Boolean>();
        Runnable r = new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB)
                    {
                        _db.beginTransactionNonExclusive();
                    }
                    else
                    {
                        _db.beginTransaction();
                    }
                    job.run();
                    _db.setTransactionSuccessful();
                    l.set(true);
                }
                catch (Exception e)
                {
                    l.set(false);
                    fireCompletionCallback(callback, false);
                }
                finally
                {
                    _db.endTransaction();
                }
                fireCompletionCallback(callback, true);
            }
        };
        ScheduledFuture<?> f = SqlDBThreads.scheduleOnWriterDBExecutor(r);
        l.wrap(f);
        return l;
    }

    /* PRIVATES */

    private void executeCallbackInAppExecutor(final DBCallback cb, final long arg)
    {
        if (cb != null)
        {
            Runnable r = new Runnable()
            {
                @Override
                public void run()
                {
                    cb.exec(arg);
                }
            };
            _appExecutor.submit((r));
        }
    }

    private void fireCompletionCallback(final ITransactionCompleteCallback
                                                cb, final boolean b)
    {
        if (cb != null)
        {
            _appExecutor.submit(new Runnable()
            {
                @Override
                public void run()
                {
                    cb.onComplete(b);
                }
            });
        }
    }

}