package datawave.microservice.common.storage;

import datawave.microservice.query.DefaultQueryParameters;
import datawave.webservice.query.Query;
import datawave.webservice.query.QueryImpl;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.log4j.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.Message;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"QueryStorageCacheTest", "sync-enabled", "send-notifications", "use-local"})
@EnableRabbit
public class QueryStorageCacheTest {
    private static final Logger log = Logger.getLogger(QueryStorageCacheTest.class);
    public static final String TEST_POOL = "testPool";
    
    @Configuration
    @Profile("QueryStorageCacheTest")
    @ComponentScan(basePackages = {"datawave.microservice"})
    public static class QueryStorageCacheTestConfiguration {}
    
    @Autowired
    private QueryCache queryCache;
    
    @Autowired
    private QueryStorageCache storageService;
    
    @Autowired
    private QueryQueueManager queueManager;
    
    private QueryQueueListener messageConsumer;
    
    private static final String LISTENER_ID = "QueryStorageCacheTestListener";
    
    @BeforeEach
    public void before() throws IOException {
        // ensure our pool is created so we can start listening to it
        queueManager.ensureQueueCreated(new QueryPool(TEST_POOL));
        messageConsumer = queueManager.createListener(LISTENER_ID, TEST_POOL);
        cleanupData();
    }
    
    @AfterEach
    public void cleanup() throws IOException {
        cleanupData();
        messageConsumer.stop();
        messageConsumer = null;
        queueManager.deleteQueue(new QueryPool(TEST_POOL));
    }
    
    public void cleanupData() throws IOException {
        storageService.clear();
        queryCache.clear();
        if (!queryCache.getTasks().isEmpty()) {
            queryCache.clear();
            if (!queryCache.getTasks().isEmpty()) {
                queryCache.getTasks();
                System.out.println("break here");
            }
        }
        clearQueue();
    }
    
    void clearQueue() throws IOException {
        queueManager.emptyQueue(new QueryPool(TEST_POOL));
        Message<byte[]> msg = messageConsumer.receive();
        while (msg != null) {
            msg = messageConsumer.receive(0L);
        }
    }
    
    @Test
    public void testStoreQuery() throws ParseException, InterruptedException, IOException, TaskLockException {
        // ensure the message queue is empty
        assertNull(messageConsumer.receiveTaskNotification(0));
        
        Query query = new QueryImpl();
        query.setQuery("foo == bar");
        query.setQueryLogicName("EventQuery");
        query.setBeginDate(new SimpleDateFormat("yyyyMMdd").parse("20200101"));
        query.setEndDate(new SimpleDateFormat("yyyMMdd").parse("20210101"));
        QueryPool queryPool = new QueryPool(TEST_POOL);
        TaskKey key = storageService.storeQuery(queryPool, query, 3);
        assertNotNull(key);
        
        TaskStates states = storageService.getTaskStates(key.getQueryId());
        assertEquals(TaskStates.TASK_STATE.READY, states.getState(key));
        
        // ensure we got a task notification
        QueryTaskNotification notification = messageConsumer.receiveTaskNotification();
        assertNotNull(notification);
        assertEquals(key, notification.getTaskKey());
        
        // ensure the message queue is empty again
        assertNull(messageConsumer.receiveTaskNotification(0));
        
        QueryTask task = storageService.getTask(key, 0);
        assertQueryTask(key, QueryTask.QUERY_ACTION.CREATE, query, task);
        
        List<QueryTask> tasks = queryCache.getTasks(key.getQueryId());
        assertNotNull(tasks);
        assertEquals(1, tasks.size());
        assertQueryTask(key, QueryTask.QUERY_ACTION.CREATE, query, tasks.get(0));
        
        tasks = queryCache.getTasks(queryPool);
        assertNotNull(tasks);
        assertEquals(1, tasks.size());
        assertQueryTask(key, QueryTask.QUERY_ACTION.CREATE, query, tasks.get(0));
        
        List<QueryState> queries = queryCache.getQueries();
        assertNotNull(queries);
        assertEquals(1, queries.size());
        assertQueryCreate(key.getQueryId(), queryPool, queries.get(0));
        
        List<TaskDescription> taskDescs = queryCache.getTaskDescriptions(key.getQueryId());
        assertNotNull(taskDescs);
        assertEquals(1, taskDescs.size());
        assertQueryCreate(key.getQueryId(), queryPool, query, taskDescs.get(0));
    }
    
    public static class QueryTaskHolder {
        public QueryTask task;
        public Exception throwable;
    }
    
    private QueryTask getTaskOnSeparateThread(final TaskKey key, final long waitMs) throws Exception {
        QueryTaskHolder taskHolder = new QueryTaskHolder();
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    taskHolder.task = storageService.getTask(key, waitMs);
                } catch (Exception e) {
                    taskHolder.throwable = e;
                }
            }
        });
        t.start();
        while (t.isAlive()) {
            Thread.sleep(1);
        }
        if (taskHolder.throwable != null) {
            throw taskHolder.throwable;
        }
        return taskHolder.task;
    }
    
    @Test
    public void testStoreTask() throws ParseException, InterruptedException, IOException, TaskLockException {
        // ensure the message queue is empty
        assertNull(messageConsumer.receiveTaskNotification(0));
        
        Query query = new QueryImpl();
        query.setQueryLogicName("EventQuery");
        query.setQuery("foo == bar");
        query.setBeginDate(new SimpleDateFormat("yyyyMMdd").parse("20200101"));
        query.setEndDate(new SimpleDateFormat("yyyMMdd").parse("20210101"));
        UUID queryId = UUID.randomUUID();
        QueryPool queryPool = new QueryPool(TEST_POOL);
        QueryKey queryKey = new QueryKey(queryPool, queryId, query.getQueryLogicName());
        QueryCheckpoint checkpoint = new QueryCheckpoint(queryKey, query);
        queryCache.updateTaskStates(new TaskStates(queryKey, 10));
        QueryTask task = storageService.createTask(QueryTask.QUERY_ACTION.NEXT, checkpoint);
        TaskKey key = task.getTaskKey();
        assertEquals(checkpoint.getQueryKey(), key);
        
        TaskStates states = storageService.getTaskStates(key.getQueryId());
        assertEquals(TaskStates.TASK_STATE.READY, states.getState(key));
        
        // ensure we got a task notification
        QueryTaskNotification notification = messageConsumer.receiveTaskNotification();
        assertNotNull(notification);
        assertEquals(key, notification.getTaskKey());
        
        // ensure the message queue is empty again
        assertNull(messageConsumer.receiveTaskNotification(0));
        
        assertFalse(storageService.getTaskLock(task.getTaskKey()).isLocked());
        
        task = storageService.getTask(key, 0);
        assertQueryTask(key, QueryTask.QUERY_ACTION.NEXT, query, task);
        assertTrue(storageService.getTaskLock(task.getTaskKey()).isLocked());
        assertTrue(storageService.getTaskLock(task.getTaskKey()).isLocked());
        assertTrue(storageService.getTaskLock(task.getTaskKey()).isLocked());
        
        try {
            task = getTaskOnSeparateThread(key, 0);
            fail("Expected failure due to task already being locked");
        } catch (TaskLockException e) {
            storageService.checkpointTask(task.getTaskKey(), task.getQueryCheckpoint());
            task = storageService.getTask(key, 0);
        } catch (Exception e) {
            fail("Unexpected exception " + e.getMessage());
        }
        assertQueryTask(key, QueryTask.QUERY_ACTION.NEXT, query, task);
        
        storageService.deleteTask(task.getTaskKey());
        
        assertFalse(storageService.getTaskLock(task.getTaskKey()).isLocked());
    }
    
    @Test
    public void testCheckpointTask() throws InterruptedException, ParseException, IOException, TaskLockException {
        // ensure the message queue is empty
        assertNull(messageConsumer.receiveTaskNotification(0));
        
        Query query = new QueryImpl();
        query.setQueryLogicName("EventQuery");
        query.setQuery("foo == bar");
        query.setBeginDate(new SimpleDateFormat("yyyyMMdd").parse("20200101"));
        query.setEndDate(new SimpleDateFormat("yyyMMdd").parse("20210101"));
        UUID queryId = UUID.randomUUID();
        QueryPool queryPool = new QueryPool(TEST_POOL);
        QueryKey queryKey = new QueryKey(queryPool, queryId, query.getQueryLogicName());
        QueryCheckpoint checkpoint = new QueryCheckpoint(queryKey, query);
        queryCache.updateTaskStates(new TaskStates(queryKey, 10));
        
        TaskKey key = new TaskKey(UUID.randomUUID(), queryPool, UUID.randomUUID(), query.getQueryLogicName());
        try {
            storageService.checkpointTask(key, checkpoint);
            fail("Expected storage service to fail checkpointing an invalid task key without a lock");
        } catch (TaskLockException e) {
            // expected
        }
        
        assertTrue(storageService.getTaskLock(key).tryLock());
        try {
            storageService.checkpointTask(key, checkpoint);
            fail("Expected storage service to fail checkpointing an invalid task key");
        } catch (IllegalArgumentException e) {
            // expected
        }
        
        key = new TaskKey(UUID.randomUUID(), checkpoint.getQueryKey());
        assertTrue(storageService.getTaskLock(key).tryLock());
        try {
            storageService.checkpointTask(key, checkpoint);
            fail("Expected storage service to fail checkpointing a missing task");
        } catch (NullPointerException e) {
            // expected
        }
        
        storageService.createTask(QueryTask.QUERY_ACTION.NEXT, checkpoint);
        
        // clear out message queue
        QueryTaskNotification notification = messageConsumer.receiveTaskNotification();
        assertNotNull(notification);
        assertNull(messageConsumer.receiveTaskNotification(0));
        
        // get the task to aquire the lock
        storageService.getTask(notification.getTaskKey(), 0);
        
        // now update the task
        Map<String,Object> props = new HashMap<>();
        props.put("checkpoint", Boolean.TRUE);
        checkpoint = new QueryCheckpoint(queryPool, queryId, query.getQueryLogicName(), props);
        storageService.checkpointTask(notification.getTaskKey(), checkpoint);
        
        // ensure we did not get another task notification
        assertNull(messageConsumer.receiveTaskNotification(0));
        
        QueryTask task = storageService.getTask(notification.getTaskKey(), 0);
        assertEquals(checkpoint, task.getQueryCheckpoint());
    }
    
    @Test
    public void testGetAndDeleteTask() throws ParseException, InterruptedException, IOException, TaskLockException {
        // ensure the message queue is empty
        assertNull(messageConsumer.receiveTaskNotification(0));
        
        Query query = new QueryImpl();
        query.setQueryLogicName("EventQuery");
        query.setQuery("foo == bar");
        query.setBeginDate(new SimpleDateFormat("yyyyMMdd").parse("20200101"));
        query.setEndDate(new SimpleDateFormat("yyyMMdd").parse("20210101"));
        UUID queryId = UUID.randomUUID();
        QueryPool queryPool = new QueryPool(TEST_POOL);
        QueryKey queryKey = new QueryKey(queryPool, queryId, query.getQueryLogicName());
        QueryCheckpoint checkpoint = new QueryCheckpoint(queryKey, query);
        queryCache.updateTaskStates(new TaskStates(queryKey, 10));
        
        storageService.createTask(QueryTask.QUERY_ACTION.NEXT, checkpoint);
        
        // clear out message queue
        QueryTaskNotification notification = messageConsumer.receiveTaskNotification();
        assertNotNull(notification);
        assertNull(messageConsumer.receiveTaskNotification(0));
        
        // ensure we can get a task
        QueryTask task = storageService.getTask(notification.getTaskKey(), 0);
        assertQueryTask(notification.getTaskKey(), QueryTask.QUERY_ACTION.NEXT, query, task);
        
        // now delete the task
        storageService.deleteTask(notification.getTaskKey());
        
        // ensure we did not get another task notification
        assertNull(messageConsumer.receiveTaskNotification(0));
        
        // ensure there is no more task stored
        task = storageService.getTask(notification.getTaskKey(), 0);
        assertNull(task);
    }
    
    @Test
    public void testGetAndDeleteQueryTasks() throws ParseException, InterruptedException, IOException, TaskLockException {
        // ensure the message queue is empty
        assertNull(messageConsumer.receiveTaskNotification(0));
        
        Query query = new QueryImpl();
        query.setQueryLogicName("EventQuery");
        query.setQuery("foo == bar");
        query.setBeginDate(new SimpleDateFormat("yyyyMMdd").parse("20200101"));
        query.setEndDate(new SimpleDateFormat("yyyMMdd").parse("20210101"));
        UUID queryId = UUID.randomUUID();
        QueryPool queryPool = new QueryPool(TEST_POOL);
        QueryKey queryKey = new QueryKey(queryPool, queryId, query.getQueryLogicName());
        QueryCheckpoint checkpoint = new QueryCheckpoint(queryKey, query);
        queryCache.updateTaskStates(new TaskStates(queryKey, 10));
        
        storageService.createTask(QueryTask.QUERY_ACTION.NEXT, checkpoint);
        
        // clear out message queue
        QueryTaskNotification notification = messageConsumer.receiveTaskNotification();
        assertNotNull(notification);
        assertNull(messageConsumer.receiveTaskNotification(0));
        
        // not get the query tasks
        List<TaskKey> tasks = storageService.getTasks(queryId);
        assertEquals(1, tasks.size());
        QueryTask task;
        task = storageService.getTask(tasks.get(0), 0);
        assertQueryTask(notification.getTaskKey(), QueryTask.QUERY_ACTION.NEXT, query, task);
        
        // now delete the query tasks
        storageService.deleteQuery(queryId);
        
        // ensure we did not get another task notification
        assertNull(messageConsumer.receiveTaskNotification(0));
        
        // make sure it deleted
        tasks = storageService.getTasks(queryId);
        assertEquals(0, tasks.size());
    }
    
    @Test
    public void testGetAndDeleteTypeTasks() throws ParseException, InterruptedException, IOException, TaskLockException {
        // ensure the message queue is empty
        assertNull(messageConsumer.receiveTaskNotification(0));
        
        Query query = new QueryImpl();
        query.setQueryLogicName("EventQuery");
        query.setQuery("foo == bar");
        query.setBeginDate(new SimpleDateFormat("yyyyMMdd").parse("20200101"));
        query.setEndDate(new SimpleDateFormat("yyyMMdd").parse("20210101"));
        UUID queryId = UUID.randomUUID();
        QueryPool queryPool = new QueryPool(TEST_POOL);
        QueryKey queryKey = new QueryKey(queryPool, queryId, query.getQueryLogicName());
        QueryStatus queryStatus = new QueryStatus(queryKey);
        QueryCheckpoint checkpoint = new QueryCheckpoint(queryKey, query);
        queryCache.updateTaskStates(new TaskStates(queryKey, 10));
        
        storageService.updateQueryStatus(queryStatus);
        storageService.createTask(QueryTask.QUERY_ACTION.NEXT, checkpoint);
        
        // clear out message queue
        QueryTaskNotification notification = messageConsumer.receiveTaskNotification();
        assertNotNull(notification);
        assertNull(messageConsumer.receiveTaskNotification(0));
        
        // now get the query tasks
        List<QueryStatus> queries = storageService.getQueryStatus();
        assertEquals(1, queries.size());
        List<TaskKey> tasks = storageService.getTasks(queries.get(0).getQueryKey().getQueryId());
        assertEquals(1, tasks.size());
        QueryTask task = storageService.getTask(tasks.get(0), 0);
        assertQueryTask(notification.getTaskKey(), QueryTask.QUERY_ACTION.NEXT, query, task);
        
        // now delete the query tasks
        storageService.deleteQuery(queryId);
        
        // ensure we did not get another task notification
        assertNull(messageConsumer.receiveTaskNotification(0));
        
        // make sure it deleted
        queries = storageService.getQueryStatus();
        assertEquals(0, queries.size());
        
    }
    
    private void assertQueryCreate(UUID queryId, QueryPool queryPool, QueryState state) {
        assertEquals(queryId, state.getQueryId());
        assertEquals(queryPool, state.getQueryPool());
        // TaskStates tasks = state.getTaskStates();
        // assertEquals(1, tasks.getTaskStates().size());
    }
    
    private void assertQueryCreate(UUID queryId, QueryPool queryPool, Query query, TaskDescription task) throws ParseException {
        assertNotNull(task.getTaskKey());
        assertEquals(queryId, task.getTaskKey().getQueryId());
        assertEquals(queryPool, task.getTaskKey().getQueryPool());
        assertEquals(QueryTask.QUERY_ACTION.CREATE, task.getAction());
        assertEquals(query.getQuery(), task.getParameters().get(QueryImpl.QUERY));
        assertEquals(DefaultQueryParameters.formatDate(query.getBeginDate()), task.getParameters().get(QueryImpl.BEGIN_DATE));
        assertEquals(DefaultQueryParameters.formatDate(query.getEndDate()), task.getParameters().get(QueryImpl.END_DATE));
    }
    
    private void assertQueryTask(TaskKey key, QueryTask.QUERY_ACTION action, Query query, QueryTask task) throws ParseException {
        assertEquals(key, task.getTaskKey());
        assertEquals(action, task.getAction());
        assertEquals(task.getQueryCheckpoint().getQueryKey(), key);
        assertEquals(query, task.getQueryCheckpoint().getPropertiesAsQuery());
    }
    
    public static class ExceptionalQueryTaskNotification extends QueryTaskNotification {
        private static final long serialVersionUID = 9177184396078311888L;
        private Exception e;
        
        public ExceptionalQueryTaskNotification(Exception e) {
            super(null, null);
            this.e = e;
        }
        
        public Exception getException() {
            return e;
        }
    }
    
}