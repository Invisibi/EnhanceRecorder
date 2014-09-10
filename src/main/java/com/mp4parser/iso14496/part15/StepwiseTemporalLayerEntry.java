package com.mp4parser.iso14496.part15;

import com.googlecode.mp4parser.boxes.mp4.samplegrouping.GroupEntry;

import java.nio.ByteBuffer;

/**
 * This sample group is used to mark temporal layer access (TSA) samples. 
 */
public class StepwiseTemporalLayerEntry extends GroupEntry {
    public static final String TYPE = "stsa";

    @Override
    public void parse(ByteBuffer byteBuffer) {
    }

    @Override
    public ByteBuffer get() {
        return ByteBuffer.allocate(0);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return 37;
    }
}
