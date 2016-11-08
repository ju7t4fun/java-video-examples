package name.just4fun;

import io.humble.video.*;
import io.humble.video.awt.MediaPictureConverter;
import io.humble.video.awt.MediaPictureConverterFactory;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * @author just4fun
 * @since 08.11.2016
 */
public class VideoMain {

    MediaPictureConverter outgoingConverter = null;

    final Rational framerate = Rational.make(1, 25);


    public void videoCopy(String incomingFilename, String filename, String formatname) throws IOException, InterruptedException {
        Demuxer demuxer = Demuxer.make();

        demuxer.open(incomingFilename, null, false, true, null, null);

        int numStreams = demuxer.getNumStreams();

        int videoStreamId = -1;
        Decoder videoDecoder = null;
        for(int i = 0; i < numStreams; i++) {
            final DemuxerStream stream = demuxer.getStream(i);

            final Decoder decoder = stream.getDecoder();
            if (decoder != null && decoder.getCodecType() == MediaDescriptor.Type.MEDIA_VIDEO) {
                videoStreamId = i;
                videoDecoder = decoder;
                // stop at the first one.
                break;
            }
        }
        if (videoStreamId == -1)
            throw new RuntimeException("could not find video stream in container: "+filename);


        videoDecoder.open(null, null);

        final MediaPicture picture = MediaPicture.make(
                videoDecoder.getWidth(),
                videoDecoder.getHeight(),
                videoDecoder.getPixelFormat());

        final MediaPictureConverter converter =
                MediaPictureConverterFactory.createConverter(
                        MediaPictureConverterFactory.HUMBLE_BGR_24,
                        picture);

        BufferedImage image = null;


        if(formatname == null){
            formatname = demuxer.getFormat().getName();
        }

        /// WRITE prepare

        final MediaPacket packet = MediaPacket.make();


        final Muxer muxer = Muxer.make(filename, null, formatname);


        final MuxerFormat format = muxer.getFormat();
        final Codec codec;

//      codec = Codec.findEncodingCodecByName("h264");
        codec = Codec.findEncodingCodec(format.getDefaultVideoCodecId());



        Encoder encoder = Encoder.make(codec);


        encoder.setWidth(videoDecoder.getWidth());
        encoder.setHeight(videoDecoder.getHeight());
        // We are going to use 420P as the format because that's what most video formats these days use
        final PixelFormat.Type pixelformat = PixelFormat.Type.PIX_FMT_YUV420P;
        encoder.setPixelFormat(pixelformat);
        encoder.setTimeBase(framerate);

        /* An annoynace of some formats is that they need global (rather than per-stream) headers,
         * and in that case you have to tell the encoder. And since Encoders are decoupled from
         * Muxers, there is no easy way to know this beyond
         */
        if (format.getFlag(MuxerFormat.Flag.GLOBAL_HEADER))
            encoder.setFlag(Encoder.Flag.FLAG_GLOBAL_HEADER, true);
        encoder.open(null, null);

        muxer.addNewStream(encoder);

        muxer.open(null, null);



        final MediaPicture outgoingPicture = MediaPicture.make(
                encoder.getWidth(),
                encoder.getHeight(),
                pixelformat);

        outgoingPicture.setTimeBase(framerate);


        final MediaPacket outgoingPacket = MediaPacket.make();

        String text = "42";

        while(demuxer.read(packet) >= 0) {
            if (packet.getStreamIndex() == videoStreamId)
            {
                /**
                 * A packet can actually contain multiple sets of samples (or frames of samples
                 * in decoding speak).  So, we may need to call decode  multiple
                 * times at different offsets in the packet's data.  We capture that here.
                 */
                int offset = 0;
                int bytesRead = 0;
                do {
                    bytesRead += videoDecoder.decode(picture, packet, offset);
                    if (picture.isComplete()) {
                        System.out.println("offset"+packet.getPosition());

                        image = makeWatermark(muxer, picture, converter,
                                image, text, outgoingConverter, outgoingPicture, encoder, outgoingPacket);

                    }
                    offset += bytesRead;
                } while (offset < packet.getSize());
            }
        }


        do {
            videoDecoder.decode(picture, null, 0);
            if (picture.isComplete()) {
                image = makeWatermark(muxer, picture, converter,
                        image, text, outgoingConverter, outgoingPicture, encoder, outgoingPacket);
            }
        } while (picture.isComplete());


        do {
            encoder.encode(packet, null);
            if (outgoingPacket.isComplete())
                muxer.write(outgoingPacket,  false);
        } while (outgoingPacket.isComplete());

        demuxer.close();

        muxer.close();

    }


    private static BufferedImage makeWatermark(final Muxer muxer,
                                               final MediaPicture picture, final MediaPictureConverter converter,
                                               BufferedImage image, final String text, MediaPictureConverter outgoingConverter,
                                               final MediaPicture outgoingPicture, final Encoder encoder, MediaPacket outgoingPacket)
            throws InterruptedException {
        image = converter.toImage(image, picture);

        BufferedImage sourceImage = image;


        // Better way's in used premaked mask to overlay with each frame.

        Graphics2D g2d = (Graphics2D) sourceImage.getGraphics();

        // initializes necessary graphic properties
        AlphaComposite alphaChannel = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.1f);
        g2d.setComposite(alphaChannel);
        g2d.setColor(Color.BLUE);
        g2d.setFont(new Font("Arial", Font.BOLD, 64));
        FontMetrics fontMetrics = g2d.getFontMetrics();
        Rectangle2D rect = fontMetrics.getStringBounds(text, g2d);

        // calculates the coordinate where the String is painted
        int centerX = (sourceImage.getWidth() - (int) rect.getWidth()) / 2;
        int centerY = sourceImage.getHeight() / 2;

        // paints the textual watermark
        g2d.drawString(text, centerX, centerY);



        final BufferedImage screen = convertToType(sourceImage, BufferedImage.TYPE_3BYTE_BGR);


        if (outgoingConverter == null)
            outgoingConverter = MediaPictureConverterFactory.createConverter(screen, outgoingPicture);
        outgoingConverter.toPicture(outgoingPicture, screen, picture.getTimeStamp());

        do {
            encoder.encode(outgoingPacket, outgoingPicture);
            if (outgoingPacket.isComplete())
                muxer.write(outgoingPacket, false);
        } while (outgoingPacket.isComplete());



        g2d.dispose();

        return image;
    }

    static BufferedImage convertToType(BufferedImage sourceImage,int targetType) {
        BufferedImage image;
        if (sourceImage.getType() == targetType) {
            image = sourceImage;
        } else {
            image = new BufferedImage(sourceImage.getWidth(), sourceImage.getHeight(), targetType);
            image.getGraphics().drawImage(sourceImage, 0, 0, null);
        }
        return image;
    }

    private static final String inputFilename = "G:\\ZIP\\lordi Blood Red Sandman.mp4";
    private static final String outputFilename = "G:\\ZIP\\lordi Blood Red Sandman Marked2.mov";

    public static void main(String[] args) throws IOException, InterruptedException {
        VideoMain videoMain = new VideoMain();
        videoMain.videoCopy(inputFilename, outputFilename, "mov");
    }


}
