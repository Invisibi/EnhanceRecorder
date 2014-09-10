package com.mp4parser.iso14496.part15;

import com.coremedia.iso.IsoTypeReader;
import com.coremedia.iso.IsoTypeWriter;
import com.googlecode.mp4parser.boxes.mp4.samplegrouping.GroupEntry;

import java.nio.ByteBuffer;

/**
 * A sync sample sample group entry identifies samples containing a sync sample of a specific type.
 */
public class SyncSampleEntry extends GroupEntry {
    public static final String TYPE = "sync";

    int reserved;
    int nalUnitType;

    @Override
    public void parse(ByteBuffer byteBuffer) {
        int a = IsoTypeReader.readUInt8(byteBuffer);
        reserved = (a & 0xC0) >> 6;
        nalUnitType = a & 0x3F;
    }

    @Override
    public ByteBuffer get() {
        ByteBuffer b = ByteBuffer.allocate(1);
        IsoTypeWriter.writeUInt8(b, (nalUnitType + (reserved << 6)));
        return (ByteBuffer) b.rewind();
    }

    @Override
    public boolean equals(Object o) {


        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        SyncSampleEntry that = (SyncSampleEntry) o;

        if (nalUnitType != that.nalUnitType) return false;
        if (reserved != that.reserved) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = reserved;
        result = 31 * result + nalUnitType;
        return result;
    }

    public int getReserved() {
        return reserved;
    }

    public void setReserved(int reserved) {
        this.reserved = reserved;
    }

    public int getNalUnitType() {
        return nalUnitType;
    }

    public void setNalUnitType(int nalUnitType) {
        this.nalUnitType = nalUnitType;
    }

    @Override
    public String toString() {
        return "SyncSampleEntry{" +
                "reserved=" + reserved +
                ", nalUnitType=" + nalUnitType +
                '}';
    }
}
