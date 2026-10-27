package com.zzf.bluetoothsmp;
import org.junit.Test;
import static org.junit.Assert.*;
public class BluetoothSessionOwnersTest {
    @Test public void reservationRejectsAnotherOwnerIncludingAddressCaseChanges() {
        Object nativePage=new Object(), plugin=new Object(); String address="AA:BB:CC:DD:EF:01";
        try {
            assertTrue(BluetoothSessionOwners.acquire("classic",address,nativePage));
            assertFalse(BluetoothSessionOwners.acquire("classic",address.toLowerCase(),plugin));
            BluetoothSessionOwners.release("classic",address,plugin);
            assertTrue(BluetoothSessionOwners.heldByOther("classic",address,plugin));
            assertTrue(BluetoothSessionOwners.acquire("ble",address,plugin));
        } finally { BluetoothSessionOwners.release("classic",address,nativePage);BluetoothSessionOwners.release("ble",address,plugin); }
    }
}
