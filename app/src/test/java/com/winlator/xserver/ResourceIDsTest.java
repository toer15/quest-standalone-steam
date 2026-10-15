package com.winlator.xserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class ResourceIDsTest {
    @Test
    public void aFullServerRefusesInsteadOfHandingOutABrokenRange() {
        ResourceIDs ids = new ResourceIDs(4); // 3 client slots
        Set<Integer> bases = new HashSet<>();
        for (int i = 0; i < 3; i++) bases.add(ids.get());
        assertEquals(3, bases.size());
        assertEquals(3, ids.inUse());
        int refused = ids.get();
        assertEquals(-1, refused);

        // The refused client disconnecting must not put -1 into the pool.
        ids.free(refused);
        assertEquals(3, ids.inUse());
        int first = bases.iterator().next();
        ids.free(first);
        assertEquals(2, ids.inUse());
        int next = ids.get();
        assertNotEquals(-1, next);
        assertEquals(first, next);
    }

    @Test
    public void manyClientsGetDisjointRanges() {
        ResourceIDs ids = new ResourceIDs(1024);
        int a = ids.get(), b = ids.get();
        assertTrue(a > 0 && b > 0);
        assertTrue(ids.isInInterval(a | ids.idMask, a));
        assertTrue(!ids.isInInterval(b, a));
        assertTrue(ids.idMask >= (1 << 18) - 1); // plenty of resource ids per client
    }
}
