package datawave.ingest.table.aggregator;

import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import datawave.ingest.protobuf.Uid;
import datawave.ingest.protobuf.Uid.List.Builder;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static datawave.ingest.table.aggregator.UidTestUtils.countOnlyList;
import static datawave.ingest.table.aggregator.UidTestUtils.legacyRemoveUidList;
import static datawave.ingest.table.aggregator.UidTestUtils.quarantineUidList;
import static datawave.ingest.table.aggregator.UidTestUtils.releaseUidList;
import static datawave.ingest.table.aggregator.UidTestUtils.removeUidList;
import static datawave.ingest.table.aggregator.UidTestUtils.uidList;
import static datawave.ingest.table.aggregator.UidTestUtils.valueToUidList;
import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class GlobalIndexUidAggregatorTest {
    
    PropogatingCombiner agg = new GlobalIndexUidAggregator();
    
    private Uid.List.Builder createNewUidList() {
        return Uid.List.newBuilder();
    }
    
    @Test
    public void testSingleUid() {
        agg.reset();
        Builder b = createNewUidList();
        b.setCOUNT(1);
        b.setIGNORE(false);
        b.addUID(UUID.randomUUID().toString());
        Uid.List uidList = b.build();
        Value val = new Value(uidList.toByteArray());
        
        Value result = agg.reduce(new Key("key"), Iterators.singletonIterator(val));
        assertNotNull(result);
        assertNotNull(result.get());
        assertNotNull(val.get());
        assertEquals(0, val.compareTo(result.get()));
    }
    
    @Test
    public void testLessThanMax() throws Exception {
        agg.reset();
        List<String> savedUUIDs = new ArrayList<>();
        Collection<Value> values = Lists.newArrayList();
        for (int i = 0; i < GlobalIndexUidAggregator.MAX - 1; i++) {
            Builder b = createNewUidList();
            b.setIGNORE(false);
            String uuid = UUID.randomUUID().toString();
            savedUUIDs.add(uuid);
            b.setCOUNT(1);
            b.addUID(uuid);
            Uid.List uidList = b.build();
            Value val = new Value(uidList.toByteArray());
            values.add(val);
        }
        Value result = agg.reduce(new Key("key"), values.iterator());
        Uid.List resultList = Uid.List.parseFrom(result.get());
        assertEquals(false, resultList.getIGNORE());
        assertEquals(resultList.getUIDCount(), (GlobalIndexUidAggregator.MAX - 1));
        List<String> resultListUUIDs = resultList.getUIDList();
        for (String s : savedUUIDs)
            assertTrue(resultListUUIDs.contains(s));
    }
    
    @Test
    public void testEqualsMax() throws Exception {
        agg.reset();
        List<String> savedUUIDs = new ArrayList<>();
        Collection<Value> values = Lists.newArrayList();
        for (int i = 0; i < GlobalIndexUidAggregator.MAX; i++) {
            Builder b = createNewUidList();
            b.setIGNORE(false);
            String uuid = UUID.randomUUID().toString();
            savedUUIDs.add(uuid);
            b.setCOUNT(1);
            b.addUID(uuid);
            Uid.List uidList = b.build();
            Value val = new Value(uidList.toByteArray());
            values.add(val);
        }
        Value result = agg.reduce(new Key("key"), values.iterator());
        Uid.List resultList = Uid.List.parseFrom(result.get());
        assertNotNull(resultList);
        assertEquals(false, resultList.getIGNORE());
        assertEquals(resultList.getUIDCount(), (GlobalIndexUidAggregator.MAX));
        List<String> resultListUUIDs = resultList.getUIDList();
        for (String s : savedUUIDs)
            assertTrue(resultListUUIDs.contains(s));
    }
    
    @Test
    public void testMoreThanMax() throws Exception {
        agg.reset();
        List<String> savedUUIDs = new ArrayList<>();
        Collection<Value> values = Lists.newArrayList();
        for (int i = 0; i < GlobalIndexUidAggregator.MAX + 10; i++) {
            Builder b = createNewUidList();
            b.setIGNORE(false);
            String uuid = UUID.randomUUID().toString();
            savedUUIDs.add(uuid);
            b.setCOUNT(1);
            b.addUID(uuid);
            Uid.List uidList = b.build();
            Value val = new Value(uidList.toByteArray());
            values.add(val);
        }
        Value result = agg.reduce(new Key("key"), values.iterator());
        Uid.List resultList = Uid.List.parseFrom(result.get());
        assertEquals(true, resultList.getIGNORE());
        assertEquals(0, resultList.getUIDCount());
        assertEquals(resultList.getCOUNT(), (GlobalIndexUidAggregator.MAX + 10));
    }
    
    @Test
    public void testManyRemoval() throws Exception {
        agg.reset();
        List<String> savedUUIDs = new ArrayList<>();
        Collection<Value> values = Lists.newArrayList();
        for (int i = 0; i < GlobalIndexUidAggregator.MAX / 2; i++) {
            Builder b = createNewUidList();
            b.setIGNORE(false);
            String uuid = UUID.randomUUID().toString();
            savedUUIDs.add(uuid);
            
            b.setCOUNT(-1);
            b.addREMOVEDUID(uuid);
            Uid.List uidList = b.build();
            Value val = new Value(uidList.toByteArray());
            values.add(val);
        }
        int i = 0;
        
        for (int j = 0; j < 1; j++) {
            for (String uuid : savedUUIDs) {
                Builder b = createNewUidList();
                b.setIGNORE(false);
                if ((i % 2) == 0)
                    b.setCOUNT(1);
                else
                    b.setCOUNT(1);
                b.addUID(uuid);
                Uid.List uidList = b.build();
                Value val = new Value(uidList.toByteArray());
                values.add(val);
                i++;
            }
        }
        
        Value result = agg.reduce(new Key("key"), values.iterator());
        Uid.List resultList = Uid.List.parseFrom(result.get());
        assertEquals(resultList.getUIDCount(), 0);
        assertEquals(resultList.getCOUNT(), 0);
        
    }
    
    @Test
    public void testSeenIgnore() throws Exception {
        Logger.getRootLogger().setLevel(Level.ALL);
        agg.reset();
        Builder b = createNewUidList();
        b.setIGNORE(true);
        b.setCOUNT(0);
        Uid.List uidList = b.build();
        Collection<Value> values = Lists.newArrayList();
        Value val = new Value(uidList.toByteArray());
        values.add(val);
        b = createNewUidList();
        b.setIGNORE(false);
        b.setCOUNT(1);
        b.addUID(UUID.randomUUID().toString());
        uidList = b.build();
        val = new Value(uidList.toByteArray());
        values.add(val);
        Value result = agg.reduce(new Key("key"), values.iterator());
        Uid.List resultList = Uid.List.parseFrom(result.get());
        assertEquals(true, resultList.getIGNORE());
        assertEquals(0, resultList.getUIDCount());
        assertEquals(1, resultList.getCOUNT());
    }
    
    @Test
    public void testInvalidValueType() throws Exception {
        Logger log = Logger.getLogger(GlobalIndexUidAggregator.class);
        Level origLevel = log.getLevel();
        log.setLevel(Level.FATAL);
        Collection<Value> values = Lists.newArrayList();
        agg.reset();
        Value val = new Value(UUID.randomUUID().toString().getBytes());
        values.add(val);
        Value result = agg.reduce(new Key("key"), values.iterator());
        Uid.List resultList = Uid.List.parseFrom(result.get());
        assertEquals(false, resultList.getIGNORE());
        assertEquals(0, resultList.getUIDCount());
        assertEquals(0, resultList.getCOUNT());
        
        log.setLevel(origLevel);
    }
    
    @Test
    public void testCount() throws Exception {
        agg.reset();
        UUID uuid = UUID.randomUUID();
        // Collect the same UUID five times.
        Collection<Value> values = Lists.newArrayList();
        for (int i = 0; i < 5; i++) {
            Builder b = createNewUidList();
            b.setCOUNT(1);
            b.setIGNORE(false);
            b.addUID(uuid.toString());
            Uid.List uidList = b.build();
            Value val = new Value(uidList.toByteArray());
            values.add(val);
        }
        Value result = agg.reduce(new Key("key"), values.iterator());
        Uid.List resultList = Uid.List.parseFrom(result.get());
        
        assertEquals(5, resultList.getCOUNT());
        assertEquals(false, resultList.getIGNORE());
        assertEquals(1, resultList.getUIDCount());
        
    }
    
    @Test
    public void testQuarantineAndRelease() {
        // First quarantine uid1
        List<Value> values = asList(uidList("uid1"), quarantineUidList("uid1"));
        
        Value firstPassValue = agg(values);
        Uid.List result = valueToUidList(firstPassValue);
        
        assertTrue(result.getQUARANTINEUIDList().contains("uid1"));
        assertFalse(result.getUIDList().contains("uid1"));
        
        // Now release uid1
        values = asList(firstPassValue, releaseUidList("uid1"));
        Value secondPassValue = agg(values);
        result = valueToUidList(secondPassValue);
        
        assertFalse(result.getQUARANTINEUIDList().contains("uid1"));
        assertTrue(result.getUIDList().contains("uid1"));
    }
    
    @Test
    public void testLegacyRemoval() {
        List<Value> values = asList(uidList("uid1", "uid2"), legacyRemoveUidList("uid1"));
        Uid.List result = valueToUidList(agg(values));
        
        assertEquals(1, result.getUIDList().size());
        assertTrue(result.getUIDList().contains("uid2"));
    }
    
    @Test
    public void testCombineLegacyAndNewRemovals() {
        List<Value> values = asList(removeUidList("uid1", "uid2"), legacyRemoveUidList("uid3"));
        Uid.List result = valueToUidList(agg(values));
        
        assertEquals(3, result.getREMOVEDUIDCount());
        assertTrue(result.getREMOVEDUIDList().contains("uid1"));
        assertTrue(result.getREMOVEDUIDList().contains("uid2"));
        assertTrue(result.getREMOVEDUIDList().contains("uid3"));
    }
    
    @Test
    public void testCombineCountAndUidListAndRemoval() {
        List<Value> values = asList(countOnlyList(100), uidList("uid1", "uid2"), removeUidList("uid3", "uid4", "uid5"));
        Uid.List result = valueToUidList(agg(values));
        
        assertEquals(99, result.getCOUNT());
        assertTrue(result.getIGNORE());
        assertTrue(result.getREMOVEDUIDList().isEmpty());
        assertTrue(result.getUIDList().isEmpty());
    }
    
    @Test
    public void testDropKeyWhenCountReachesZero() {
        List<Value> values = asList(countOnlyList(2), removeUidList("uid1", "uid2"));
        Uid.List result = valueToUidList(agg(values));
        
        assertEquals(0, result.getCOUNT());
        assertTrue(result.getIGNORE());
        assertFalse(agg.propogateKey());
    }
    
    @Test
    public void testDropKeyWhenCountReachesZeroWithCount() {
        List<Value> values = asList(countOnlyList(100), countOnlyList(-100));
        Uid.List result = valueToUidList(agg(values));
        
        assertEquals(0, result.getCOUNT());
        assertFalse(agg.propogateKey());
    }
    
    @Test
    public void testDropKeyWhenCountGoesNegative() {
        List<Value> values = asList(countOnlyList(1), removeUidList("uid1", "uid2"));
        Uid.List result = valueToUidList(agg(values));
        
        assertEquals(-1, result.getCOUNT());
        assertTrue(result.getIGNORE());
        assertFalse(agg.propogateKey());
    }
    
    @Test
    public void testWeKeepUidListDuringDoubleRemovals() {
        List<Value> values = asList(uidList("uid1"), removeUidList("uid2"), removeUidList("uid2"));
        Uid.List result = valueToUidList(agg(values));
        
        assertEquals(1, result.getUIDList().size());
        assertTrue(agg.propogateKey());
    }
    
    private Value agg(List<Value> values) {
        agg.reset();
        return agg.reduce(new Key("row"), values.iterator());
    }
}
