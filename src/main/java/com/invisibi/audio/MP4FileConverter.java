package com.invisibi.audio;

import android.util.Log;

import com.coremedia.iso.boxes.Container;
import com.googlecode.mp4parser.FileDataSourceImpl;
import com.googlecode.mp4parser.authoring.Movie;
import com.googlecode.mp4parser.authoring.Track;
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder;
import com.googlecode.mp4parser.authoring.tracks.AACTrackImpl;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

/**
 * Write data to mp4 file.
 */
public class MP4FileConverter {

    private static final String TAG = "MP4FileConverter";
    private FileOutputStream mFileOutputStream;
    private String mInputFilePath;

    public MP4FileConverter(String inputFilePath, String outputFilePath) throws IOException {
        mInputFilePath = inputFilePath;
        mFileOutputStream = new FileOutputStream(outputFilePath);
    }

    public void convert() throws IOException, IllegalArgumentException {
        double time1 = System.currentTimeMillis();
        Track audioTrack = new AACTrackImpl(new FileDataSourceImpl(mInputFilePath));
        Movie movie = new Movie();
        movie.addTrack(audioTrack);
        Container output = new DefaultMp4Builder().build(movie);
        FileChannel fileChannel = mFileOutputStream.getChannel();
        output.writeContainer(fileChannel);
        fileChannel.close();
        double totalTime = System.currentTimeMillis() - time1;
        Log.d(TAG, "spend " + totalTime + "ms to convert aac to mp4");
    }
}
