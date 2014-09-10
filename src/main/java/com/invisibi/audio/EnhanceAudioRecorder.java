package com.invisibi.audio;

import android.content.Context;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Add more features than Android's default MediaRecorder
 * Additional features:
 * 1. Get current position
 * 2. Support pause
 * 3. Support audio metering like iOS
 */
public class EnhanceAudioRecorder {

    private static final String TAG = "HedwigAudioRecorder";
    private static final String DEFAULT_AUDIO_MIME_TYPE = "audio/mp4a-latm";
    private static final int DEFAULT_CHANNEL_COUNT = 1; // mono
    private static final int DEFAULT_SAMPLE_RATE = 16000;
    private static final int DEFAULT_BIT_RATE = 64 * 1024;
    private static final int MAX_AMPLITUTE = (int) Math.pow(2, 16) / 2; //16bit
    private static final int ADTS_HEADER_SIZE = 7;

    public static final int DEFALUT_MAX_DURATON = 60000;

    private Context mContext;
    private AudioRecord mAudioRecord;
    private MediaCodec mEncoder;
    private String mOutputFilePath;
    private String mTmpFilePath;
    private int mMaxDuration; // milliseconds
    private int mMinBufferSize;

    private Thread mRecordingThread;
    private int mCurrentPosition;//current recording position, base on sample rate and bytes per sample, millisecond

    private double mPeakVolume;
    private double mAverageVolume;
    private boolean mNeedToReleaseAfterStop;

    private short[] mInputPCMBuffer;

    private RecorderState mRecordState = RecorderState.Released;
    private RecordingParameters mParams;

    //TODO: Use for output aac raw file, replace it with MediaMuxer after Android 4.3+ is much more popular
    private FileOutputStream mAudioOutputStream;
    private MP4FileConverter mMP4FileConverter;

    private EventHandler mEventHandler;
    private OnInfoListener mOnInfoListener;

    public static final int MEDIA_RECORDER_INFO_MAX_DURATION_REACHED = 7878;

    public enum RecorderState {
        Prepared,
        Recording,
        Paused,
        Stopping,
        Stopped,
        Released,
        Error
    }

    /**
     * Interface definition for a callback, use the similar interface with MediaRecorder
     */
    public interface OnInfoListener
    {
        /**
         * Called when an error occurs while recording.
         *
         * @param recorder the EnhanceAudioRecorder that encountered the error
         * @param what the type of information that has occurred:
         * <ul>
         * <li>{@link #MEDIA_RECORDER_INFO_MAX_DURATION_REACHED}
         * </ul>
         * @param extra an extra code
         */
        void onInfo(EnhanceAudioRecorder recorder, int what, int extra);
    }

    public static class RecordingParameters {
        private int mAudioSource;
        private int mSampleRate;
        private int mChannels;
        private int mEncodingBitrate;
        private int mMaxDuration;
        private String mOutputFilePath;

        public RecordingParameters() {
            mAudioSource = MediaRecorder.AudioSource.MIC;
            mSampleRate = DEFAULT_SAMPLE_RATE;
            mChannels = DEFAULT_CHANNEL_COUNT;
            mEncodingBitrate = DEFAULT_BIT_RATE;
            mMaxDuration = DEFALUT_MAX_DURATON + 200; //add 0.2s for max duration to avoid inaccurary in final file.
            mOutputFilePath = "";
        }

        public void setAudioSource(int audioSource) {
            mAudioSource = audioSource;
        }

        public void setSampleRate(int sampleRate) {
            mSampleRate = sampleRate;
        }

        public void setChannels(int channels) {
            mChannels = channels;
        }

        public void setEncodingBitrate(int encodingBitrate) {
            mEncodingBitrate = encodingBitrate;
        }

        public void setMaxDuration(int maxDuration) {
            mMaxDuration = maxDuration;
        }

        public void setOutputFilePath(String outputFilePath) {
            mOutputFilePath = outputFilePath;
        }

        public int getAudioSource() {
            return mAudioSource;
        }

        public int getSampleRate() {
            return mSampleRate;
        }

        public int getChannels() {
            return mChannels;
        }

        public int getEncodingBitrate() {
            return mEncodingBitrate;
        }

        public int getMaxDuration() {
            return mMaxDuration;
        }

        public String getOutputFilePath() {
            return mOutputFilePath;
        }
    }

    public EnhanceAudioRecorder(Context context) {
        mContext = context;
        mParams = new RecordingParameters();
        Looper looper;
        if ((looper = Looper.myLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else if ((looper = Looper.getMainLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else {
            mEventHandler = null;
        }
    }

    public synchronized void prepare() throws IOException, IllegalStateException {
        if (mRecordState != RecorderState.Released) {
            throw new IllegalStateException("call prepare in illegal state " + mRecordState);
        }
        initAudioRecord();
        changeState(RecorderState.Prepared);
    }

    public synchronized void start() {
        if (mRecordState == RecorderState.Released) {
            try {
                prepare();
            } catch (IOException e) {
                Log.e(TAG, "Cannot start recorder, reason = " + e.getMessage());
                changeState(RecorderState.Error);
                return;
            }
        }

        startRecording();
        changeState(RecorderState.Recording);
    }

    public synchronized void stop() {
        if (mRecordState != RecorderState.Prepared && mRecordState != RecorderState.Recording
                && mRecordState != RecorderState.Paused ) {
            Log.w(TAG, "no need to stop recorder");
            return;
        }
        changeState(RecorderState.Stopping);
        stopRecording();
        mCurrentPosition = 0;
        mNeedToReleaseAfterStop = true;
    }

    public synchronized void pause() {
        stopRecording();
        changeState(RecorderState.Paused);
    }

    public synchronized boolean isRecording() {
        return mRecordState == RecorderState.Recording;
    }

    /**
     * Get current recording postion, time unit is millisecond
     * @return current postion
     */
    public int getCurrentPosition() {
        return mCurrentPosition;
    }

    /**
     * Get metering values from recorder
     * @param values double array, values[0] is peak volume, value[1] is average volume
     */
    public void getMetering(double[] values) {
        if (values != null && values.length >= 2) {
            values[0] = mPeakVolume;
            values[1] = mAverageVolume;
        }
    }

    public RecordingParameters getRecordingParameter() {
        return mParams;
    }

    public void setRecordingParameter(RecordingParameters parameter) {
        mParams = parameter;
    }

    /**
     * set max recording duration
     * @param maxDuration max duration, time unit is ms
     */
    public void setMaxDuration(int maxDuration) {
        mMaxDuration = maxDuration;
    }


    public void setOnInfoListener(OnInfoListener listener) {
        mOnInfoListener = listener;
    }

    private boolean isAudioRecordRecording() {
        return mAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING;
    }

    private void startRecording() {
        mAudioRecord.startRecording();

        try {
            mEncoder.start();
        } catch (IllegalStateException e) {
            Log.w(TAG, "encoder is already started");
        }

        mRecordingThread = new Thread() {
            @Override
            public void run() {
                int read;
                while (isAudioRecordRecording()) {
                    read = mAudioRecord.read(mInputPCMBuffer, 0, mMinBufferSize / 2);
                    if (read > 0) {
                        if (isAudioRecordRecording()) {
                            mCurrentPosition += ((double)read / mParams.getSampleRate() * 1000) ;
                            Log.v(TAG, "read " + read + " bytes from audio source");
                            Log.v(TAG, "current postion = " + mCurrentPosition);
                            if (mCurrentPosition >= mMaxDuration) {
                                EnhanceAudioRecorder.this.stop();
                                if (mEventHandler != null) {
                                    Message msg = mEventHandler.obtainMessage();
                                    msg.what = EventHandler.MEDIA_RECORDER_EVENT_INFO;
                                    msg.arg1 = MEDIA_RECORDER_INFO_MAX_DURATION_REACHED;
                                    msg.arg2 = 0;
                                    mEventHandler.sendMessage(msg);
                                }
                            }
                        }

                        updateMetering(mInputPCMBuffer, read);

                        try {
                            feedEncoder(mInputPCMBuffer, read);
                        } catch (IllegalStateException e) {
                            Log.e(TAG, "Cannot write audio data to encoder");
                        }
                    }
                }

                if (mNeedToReleaseAfterStop) {
                    release();
                }

                Log.d(TAG, "recording thread stopped");
            }
        };
        mRecordingThread.start();
    }

    private void outputMP4File() {
        try {
            mAudioOutputStream.close();
            mMP4FileConverter.convert();
        } catch (IOException e) {
            Log.e(TAG, "cannot write mp4 file");
        }
    }

    private void initEncoder() {
        List<MediaCodecInfo> codecInfoList = getCodecCandidates(DEFAULT_AUDIO_MIME_TYPE);
        mEncoder = MediaCodec.createByCodecName("OMX.google.aac.encoder"); //use google's aac encoder first
        if (mEncoder == null) {
            mEncoder = MediaCodec.createByCodecName(codecInfoList.get(0).getName());
        }

        MediaFormat mediaFormat = MediaFormat.createAudioFormat(DEFAULT_AUDIO_MIME_TYPE, mParams.getSampleRate(), mParams.getChannels());
        mediaFormat.setString(MediaFormat.KEY_MIME, DEFAULT_AUDIO_MIME_TYPE);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, mParams.getEncodingBitrate());
        mediaFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        mEncoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }

    private void releaseEncoder() {
        try {
            mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        } catch (Exception e) {
            Log.e(TAG, "Cannot stop encoder correctly");
        }
    }

    private void feedEncoder(short[] audioData, int count) {
        int inputBufferIndex;
        int outputBufferIndex;
        ByteBuffer[] encoderInputBuffers;
        ByteBuffer[] encoderOutputBuffers;
        encoderInputBuffers = mEncoder.getInputBuffers();
        encoderOutputBuffers = mEncoder.getOutputBuffers();
        inputBufferIndex = mEncoder.dequeueInputBuffer(-1);

        if (inputBufferIndex >= 0) {
            ByteBuffer inputBuffer = encoderInputBuffers[inputBufferIndex];
            inputBuffer.clear();
            inputBuffer.asShortBuffer().put(audioData, 0, count);
            mEncoder.queueInputBuffer(inputBufferIndex, 0, 2 * count, 0, 0);
        }

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        outputBufferIndex = mEncoder.dequeueOutputBuffer(bufferInfo, 0);

        //not handle MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED and MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
        while (outputBufferIndex >= 0) {
            int outPacketSize = bufferInfo.size + ADTS_HEADER_SIZE; //ProtectionAbsent = 1
            ByteBuffer outputBuffer = encoderOutputBuffers[outputBufferIndex];
            outputBuffer.position(bufferInfo.offset);
            outputBuffer.limit(bufferInfo.offset + bufferInfo.size);

            byte[] outBuffer = new byte[outPacketSize];
            addADTSToPacket(outBuffer, outPacketSize);
            outputBuffer.get(outBuffer, ADTS_HEADER_SIZE, bufferInfo.size);
            outputBuffer.position(bufferInfo.offset);

            try {
                mAudioOutputStream.write(outBuffer, 0, outPacketSize);
            } catch (IOException e) {
                Log.e(TAG, "cannot write audio data");
            }

            outputBuffer.clear();
            mEncoder.releaseOutputBuffer(outputBufferIndex, false);
            outputBufferIndex = mEncoder.dequeueOutputBuffer(bufferInfo, 0);
        }
    }

    /*
        ADTS format
        AAAAAAAA AAAABCCD EEFFFFGH HHIJKLMM MMMMMMMM MMMOOOOO OOOOOOPP (QQQQQQQQ QQQQQQQQ)
        Header consists of 7 or 9 bytes (without or with CRC).
        Letter	Length (bits)	Description
        A	        12	        syncword 0xFFF, all bits must be 1
        B	        1	        MPEG Version: 0 for MPEG-4, 1 for MPEG-2
        C	        2	        Layer: always 0
        D	        1	        protection absent, Warning, set to 1 if there is no CRC and 0 if there is CRC
        E	        2	        profile, the MPEG-4 Audio Object Type minus 1
        F	        4	        MPEG-4 Sampling Frequency Index (15 is forbidden)
        G	        1	        private stream, set to 0 when encoding, ignore when decoding
        H	        3	        MPEG-4 Channel Configuration (in the case of 0, the channel configuration is sent via an inband PCE)
        I	        1	        originality, set to 0 when encoding, ignore when decoding
        J	        1	        home, set to 0 when encoding, ignore when decoding
        K	        1	        copyrighted stream, set to 0 when encoding, ignore when decoding
        L	        1	        copyright start, set to 0 when encoding, ignore when decoding
        M	        13	        frame length, this value must include 7 or 9 bytes of header length: FrameLength = (ProtectionAbsent == 1 ? 7 : 9) + size(AACFrame)
        O       	11	        Buffer fullness
        P	        2	        Number of AAC frames (RDBs) in ADTS frame minus 1, for maximum compatibility always use 1 AAC frame per ADTS frame
        Q	        16  	    CRC if protection absent is 0
    */
    private void addADTSToPacket(byte[] packet, int length) {
        int profile = 2;  //AAC LC
        int freqIdx = getFrequencyIdx(mParams.getSampleRate());
        int chanCfg = mParams.getChannels();

        packet[0] = (byte)0xFF;
        packet[1] = (byte)0xF1; //layer = 0; Mpeg-4 version, Protection absent
        packet[2] = (byte)(((profile - 1) << 6) + ((freqIdx & 0x0F) << 2) + (chanCfg >> 2));
        packet[3] = (byte)(((chanCfg & 3) << 6) + (length >> 11));
        packet[4] = (byte)((length & 0x7FF) >> 3);
        packet[5] = (byte)(((length & 7) << 5) + 0x1F);
        packet[6] = (byte)0xFC;
    }

    private int getFrequencyIdx(int sampleRate) {
        int idx = 15;
        switch (sampleRate) {
            case 8000:
                idx = 11;
                break;
            case 16000:
                idx = 8;
                break;
            case 22050:
                idx = 7;
                break;
            case 44100:
                idx = 4;
                break;
            case 48000:
                idx = 3;
                break;
        }
        return idx;
    }

    private void stopRecording() {
        mAudioRecord.stop();
        if (mRecordingThread != null && mRecordingThread.getState() == Thread.State.TERMINATED) {
            Log.d(TAG, "recording thread state: " + mRecordingThread.getState());
            release();
        }
    }

    private synchronized void release() {
        outputMP4File();
        changeState(RecorderState.Stopped);
        mAudioRecord.release();
        releaseEncoder();
        File tmpFile = new File(mTmpFilePath);
        if (tmpFile.exists()) {
            tmpFile.delete();
        }
        changeState(RecorderState.Released);
    }

    private void initAudioRecord() throws IOException {
        mMinBufferSize = AudioRecord.getMinBufferSize(mParams.getSampleRate(),
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        mAudioRecord = new AudioRecord(mParams.getAudioSource(), mParams.getSampleRate(), AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, 2 * mMinBufferSize);

        initEncoder();

        mMaxDuration = mParams.getMaxDuration();
        mOutputFilePath = mParams.getOutputFilePath();

        mTmpFilePath = mContext.getApplicationInfo().dataDir + File.separator + "tmp.aac";

        if (!TextUtils.isEmpty(mOutputFilePath)) {
            mAudioOutputStream = new FileOutputStream(mTmpFilePath);
            mMP4FileConverter = new MP4FileConverter(mTmpFilePath, mOutputFilePath);
        }
        mCurrentPosition = 0;
        mInputPCMBuffer = new short[mMinBufferSize / 2];
        mNeedToReleaseAfterStop = false;
    }

    private void updateMetering(short[] audioData, int sizeInShort ) {
        if (audioData != null && audioData.length > 0) {
            short peak = 0;
            int accumulate = 0;
            for (int i = 0; i < sizeInShort; i++) {
                int amplitude = Math.abs(audioData[i]);
                peak = (short)Math.max(amplitude, peak);
                accumulate += amplitude;
            }
            mPeakVolume = calculateDb(peak);
            mAverageVolume = calculateDb(accumulate / audioData.length);
        }
    }

    private double calculateDb(int value) {
        value = value == 0 ? 1 : value;
        return 20 * Math.log10((double)value / MAX_AMPLITUTE);
    }

    private void changeState(RecorderState newState) {
        mRecordState = newState;
    }

    private static List<MediaCodecInfo> getCodecCandidates(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        List<MediaCodecInfo> codecList = null;
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);

            if (!codecInfo.isEncoder()) {
                continue;
            }

            String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(mimeType)) {
                    if (codecList == null) {
                        codecList = new ArrayList<MediaCodecInfo>();
                    }
                    codecList.add(codecInfo);
                }
            }
        }
        return codecList;
    }

    private class EventHandler extends Handler
    {
        private EnhanceAudioRecorder mEnhanceRecorder;

        public EventHandler(EnhanceAudioRecorder recorder, Looper looper) {
            super(looper);
            mEnhanceRecorder = recorder;
        }

        private static final int MEDIA_RECORDER_EVENT_INFO = 1;

        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case MEDIA_RECORDER_EVENT_INFO:
                    if (mOnInfoListener != null) {
                        mOnInfoListener.onInfo(mEnhanceRecorder, msg.arg1, msg.arg2);
                    }

                    return;

                default:
                    Log.e(TAG, "Unknown message type " + msg.what);
                    return;
            }
        }
    }
 }
