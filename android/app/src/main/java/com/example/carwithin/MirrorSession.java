package com.example.carwithin;

import android.graphics.*;
import android.media.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.locks.LockSupport;

public final class MirrorSession {
    public interface Listener {void log(String message);void stats(String message);void finished();}
    public interface FrameSource {
        boolean draw(Canvas canvas, Paint paint, int frame);
        default long freshFrames() { return -1; }
        default long captureStartedNs() { return 0; }
        default long readyNs() { return 0; }
        default boolean freshFrame() { return true; }
        default boolean hasFreshFrame() { return true; }
        default void submitted() {}
        default long maxReadyGapNs() { return 0; }
        default void close() {}
    }
    private final android.content.Context context;
    private final P2pController.Route route;
    private final Listener listener;
    private final File capture;
    private final boolean repeatKey;
    private final StreamProfile profile;
    private final FrameSource source;
    private volatile boolean stopped;
    private volatile DatagramSocket video,control;
    private volatile TailingConnection tailing;
    private volatile EasyConnPxcConnection easyConn;

    public MirrorSession(android.content.Context context,P2pController.Route route,File capture,boolean repeatKey,StreamProfile profile,FrameSource source,Listener listener) {
        this.context=context.getApplicationContext();this.route=route;this.capture=capture;this.repeatKey=repeatKey;this.profile=profile;this.listener=listener;
        this.source=source!=null?source:(canvas,paint,frame)->{
            canvas.save();canvas.scale(canvas.getWidth()/848f,canvas.getHeight()/480f);TestPattern.draw(canvas,paint,frame,profile.fps());canvas.restore();return true;
        };
    }
    public void stop() {
        stopped=true;
        TailingConnection connection=tailing;
        if(connection!=null) {
            if(profile.transport()==StreamProfile.Transport.ZEEHO_TCP)connection.cancelVideoRead();
            else connection.close();
        }
        EasyConnPxcConnection ec=easyConn;
        if(ec!=null) {
            ec.cancelVideoRead();
            ec.close();
        }
    }
    private DatagramSocket socket(int port) throws Exception {
        DatagramSocket s=new DatagramSocket(null);
        try {
            if(route.network()!=null)route.network().bindSocket(s);
            s.bind(new InetSocketAddress(route.local(),port));
            s.connect(route.remote(),port);s.setSoTimeout(1);s.setSendBufferSize(256*1024);
            return s;
        } catch(Exception e){s.close();throw e;}
    }
    public void run() {
        if (stopped) return;
        if(profile.transport()==StreamProfile.Transport.TAILING_TCP && route!=null){runTailing();return;}
        if(profile.codec()==StreamProfile.Codec.JPEG){runJpeg();return;}
        boolean zeeho=profile.transport()==StreamProfile.Transport.ZEEHO_TCP && route!=null;
        boolean isEasyConn=profile.transport()==StreamProfile.Transport.EASYCONN_TCP && route!=null;
        boolean tcpPull=zeeho || isEasyConn;
        TailingConnection pxc=zeeho?new TailingConnection(context,route,profile,listener):null;
        EasyConnPxcConnection ec=isEasyConn?new EasyConnPxcConnection(context,route,listener):null;
        tailing=pxc;
        easyConn=ec;
        StreamProfile activeProfile=profile;
        MediaCodec codec=null;GpuEncoderSurface gpu=null;Bitmap bitmap=null;FileOutputStream saved=null;
        long[] packets={0},bytes={0};int frames=0,replies=0,counter=0;long lastReply=0;
        try {
            if(stopped)return;
            boolean self=route==null;
            if(ec!=null) {
                ec.start();
                activeProfile=profile.withNegotiated(ec.getNegotiatedWidth(),ec.getNegotiatedHeight(),ec.getNegotiatedFps());
            }
            else if(pxc!=null)pxc.start();
            else if(!self) {
                video=socket(5012);control=socket(5013);
                listener.log("UDP 已绑定 "+route.local().getHostAddress()+":5012/5013 → "+route.remote().getHostAddress()+
                    (route.network()==null?"（P2P 本地地址绑定）":"（P2P Network 绑定）"));
            }
            if(capture!=null)saved=new FileOutputStream(capture);
            int width=activeProfile.width(),height=activeProfile.height();
            int targetBitrate=isEasyConn?Math.max(1500000,Math.min(5000000,2500000*activeProfile.fps()/25)):activeProfile.bitrate();
            MediaFormat format=MediaFormat.createVideoFormat("video/avc",width,height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE,targetBitrate);format.setInteger(MediaFormat.KEY_FRAME_RATE,activeProfile.fps());
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);
            format.setInteger(MediaFormat.KEY_PRIORITY,0);
            format.setInteger(MediaFormat.KEY_LATENCY,1); // Encoder hint; actual support is device dependent.
            if(Build.VERSION.SDK_INT>=29)format.setInteger(MediaFormat.KEY_MAX_B_FRAMES,0);
            format.setInteger(MediaFormat.KEY_PROFILE,MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            int level=activeProfile.avcLevel();
            format.setInteger(MediaFormat.KEY_LEVEL,level==30?MediaCodecInfo.CodecProfileLevel.AVCLevel3:level==31?MediaCodecInfo.CodecProfileLevel.AVCLevel31:MediaCodecInfo.CodecProfileLevel.AVCLevel4);
            String name=new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format);
            try {
                if(name==null)throw new IllegalStateException("没有Surface H.264编码器");
                codec=MediaCodec.createByCodecName(name);codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
                gpu=new GpuEncoderSurface(codec.createInputSurface(),width,height);codec.start();
                listener.log("输入路径：OpenGL → 编码器Surface（省去CPU RGB→YUV，来源仍为Bitmap）");
            } catch(Exception e) {
                if(gpu!=null){gpu.close();gpu=null;}
                if(codec!=null){codec.release();codec=null;}
                listener.log("Surface初始化不可用，回退原YUV输入："+e.getMessage());
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
                name=new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format);
                if(name==null)throw new IllegalStateException("手机没有支持此配置的 H.264 编码器");
                codec=MediaCodec.createByCodecName(name);codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start();
            }
            if(Build.VERSION.SDK_INT>=29)listener.log("编码器能力：硬件加速="+codec.getCodecInfo().isHardwareAccelerated()+"，纯软件="+codec.getCodecInfo().isSoftwareOnly());
            listener.log("编码器 "+name+"，"+width+"×"+height+" Baseline / "+activeProfile.fps()+"fps / "+targetBitrate/1000+"kbps；约每0.5秒请求关键帧");
            bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(bitmap);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
            int[] rgb=gpu==null?new int[width*height]:null;
            byte[] yRow=gpu==null?new byte[width]:null;
            NinebotProtocol protocol=new NinebotProtocol();MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            Kcp kcp=null;
            if(profile.transport()==StreamProfile.Transport.KCP && control!=null) {
                byte[] initial=NinebotProtocol.heartbeat(0);
                control.send(new DatagramPacket(initial,initial.length));
                byte[] reset=NinebotProtocol.bye();
                control.send(new DatagramPacket(reset,reset.length));
                long deadline=SystemClock.elapsedRealtime()+3000;
                Long conversation=null;
                while(!stopped && SystemClock.elapsedRealtime()<deadline) {
                    DatagramPacket response=new DatagramPacket(new byte[2048],2048);
                    try{control.receive(response);}catch(SocketTimeoutException e){continue;}
                    byte[] reply=Arrays.copyOf(response.getData(),response.getLength());
                    if(reply.length==24 && NinebotProtocol.heartbeatReply(reply)==0) {
                        conversation=NinebotProtocol.kcpConversation(reply);
                        break;
                    }
                }
                if(conversation==null)throw new IOException("七寸未完成KCP会话协商，请检查仪表投屏模式");
                kcp=new Kcp(conversation,(pkt,len)->{
                    if(stopped)throw new InterruptedIOException("已停止");
                    if(video!=null)video.send(new DatagramPacket(pkt,len));
                    packets[0]++;bytes[0]+=len;
                });
                kcp.setNodelay(1,10,2,true);
                kcp.setMtu(1400);kcp.setWndSize(10240,10240);
                listener.log("七寸KCP协商完成：conv="+conversation+"，MTU1400，窗口10240");
            }
            final Kcp kcpRef=kcp;
            byte[] csd=new byte[0];ByteArrayOutputStream partial=new ByteArrayOutputStream();
            long partialPts=-1;boolean partialKey=false;int submitted=0;
            long began=SystemClock.elapsedRealtime(),nextFrame=began,nextBeat=began+1000,nextSync=began+500,nextStats=began+1000,firstPts=-1,lastSubmittedAt=-1;
            Map<Integer,Long> probes=new LinkedHashMap<>();
            long lastStatsAt=began,lastSourceFrames=0,lastEncodedFrames=0,sourceFrames=0;
            record Pending(long captured,long ready,long drawStarted,long queued,boolean fresh) {}
            Map<Long,Pending> inFlight=new HashMap<>();
            long conversionNs=0,conversionCount=0,codecNs=0,codecCount=0;
            long sourceNs=0,busNs=0,phoneNs=0,phoneMaxNs=0,freshCount=0,sendNs=0,sendMaxNs=0,sendCount=0;
            long inputMaxNs=0,lastOutputNs=0,outputGapMaxNs=0;int keyCount=0,maxFrameBytes=0;
            while(!stopped && (!self || SystemClock.elapsedRealtime()-began<3000)) {
                long now=SystemClock.elapsedRealtime();
                if((kcpRef==null || kcpRef.waitSnd()<256) && inFlight.size()<2 && FramePacing.shouldSubmit(now,nextFrame,lastSubmittedAt,source.hasFreshFrame(),activeProfile.fps())) {
                    if(now>=nextSync) {Bundle b=new Bundle();b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0);codec.setParameters(b);nextSync=now+500;}
                    long drawStartedNs=SystemClock.elapsedRealtimeNanos();
                    int input=source.draw(canvas,paint,submitted)?(gpu!=null?0:codec.dequeueInputBuffer(0)):-1;
                    if(input>=0) {
                        long pts=(now-began)*1000;
                        if(gpu!=null)gpu.submit(bitmap,pts,source.freshFrame());
                        else {
                            bitmap.getPixels(rgb,0,width,0,0,width,height);
                            Image image=codec.getInputImage(input);
                            if(image==null)throw new IllegalStateException("编码器未提供 YUV 输入图像，需适配此手机输入格式");
                            try{writeYuv(image,rgb,width,height,yRow);}finally{image.close();}
                            codec.queueInputBuffer(input,0,width*height*3/2,pts,0);
                        }
                        long queuedNs=SystemClock.elapsedRealtimeNanos();
                        conversionNs+=queuedNs-drawStartedNs;conversionCount++;
                        inputMaxNs=Math.max(inputMaxNs,queuedNs-drawStartedNs);
                        long captured=source.captureStartedNs(),ready=source.readyNs();
                        inFlight.put(pts,new Pending(captured>0?captured:drawStartedNs,ready>0?ready:drawStartedNs,drawStartedNs,queuedNs,source.freshFrame()));submitted++;
                        source.submitted();lastSubmittedAt=now;
                        nextFrame=FramePacing.nextDeadline(now,nextFrame,activeProfile.fps());
                    }
                }
                int output;
                while((output=codec.dequeueOutputBuffer(info,0))!=MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if(output==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat actual=codec.getOutputFormat();csd=new byte[0];
                        for(String k:new String[]{"csd-0","csd-1"})if(actual.containsKey(k))csd=Avc.join(csd,Avc.annexB(Avc.bytes(actual.getByteBuffer(k))));
                        listener.log("实际输出 "+actual);continue;
                    }
                    if(output<0)continue;
                    byte[] data;
                    try {
                        ByteBuffer b=codec.getOutputBuffer(output);
                        if(b==null||info.size==0)continue;
                        b.position(info.offset);b.limit(info.offset+info.size);data=Avc.bytes(b);
                    } finally {codec.releaseOutputBuffer(output,false);}
                    if((info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0){csd=Avc.annexB(data);continue;}
                    boolean key=(info.flags&MediaCodec.BUFFER_FLAG_KEY_FRAME)!=0;
                    if(partial.size()>0 && partialPts!=info.presentationTimeUs)throw new IllegalStateException("编码器分片 PTS 不一致");
                    partialPts=info.presentationTimeUs;partialKey|=key;partial.write(data,0,data.length);
                    if((info.flags&MediaCodec.BUFFER_FLAG_PARTIAL_FRAME)!=0)continue;
                    Pending pending=inFlight.remove(info.presentationTimeUs);
                    if(pending!=null){codecNs+=SystemClock.elapsedRealtimeNanos()-pending.queued();codecCount++;}
                    data=Avc.annexB(partial.toByteArray());partial.reset();key=partialKey||Avc.hasNal(data,5);partialKey=false;
                    if(key && (!Avc.hasNal(data,7)||!Avc.hasNal(data,8)))data=Avc.join(csd,data);
                    if(key && (!Avc.hasNal(data,7)||!Avc.hasNal(data,8)))throw new IllegalStateException("关键帧缺少 SPS/PPS");
                    if(firstPts<0)firstPts=info.presentationTimeUs;
                    long timestamp=Math.max(0,(info.presentationTimeUs-firstPts)/1000);
                    if(saved!=null)saved.write(data);
                    long sendStartedNs=SystemClock.elapsedRealtimeNanos();
                    if(ec!=null) {
                        ec.sendAvc(data);
                        packets[0]++;
                        bytes[0]+=data.length;
                    } else if(pxc!=null) {
                        pxc.sendAvc(data);
                        packets[0]++;
                        bytes[0]+=data.length;
                    } else protocol.sendFrame(data,timestamp,key,repeatKey,p->{
                        if(stopped)throw new InterruptedIOException("已停止");
                        if(kcpRef!=null){kcpRef.send(p);kcpRef.update(SystemClock.elapsedRealtime());}
                        else{if(video!=null)video.send(new DatagramPacket(p,p.length));packets[0]++;bytes[0]+=p.length;}
                    });frames++;
                    long sentNs=SystemClock.elapsedRealtimeNanos(),sendElapsed=sentNs-sendStartedNs;
                    if(lastOutputNs>0)outputGapMaxNs=Math.max(outputGapMaxNs,sentNs-lastOutputNs);
                    lastOutputNs=sentNs;maxFrameBytes=Math.max(maxFrameBytes,data.length);if(key)keyCount++;
                    sendNs+=sendElapsed;sendMaxNs=Math.max(sendMaxNs,sendElapsed);sendCount++;
                    if(pending!=null && pending.fresh()) {
                        sourceNs+=Math.max(0,pending.ready()-pending.captured());
                        busNs+=Math.max(0,pending.drawStarted()-pending.ready());
                        long age=Math.max(0,sentNs-pending.captured());phoneNs+=age;phoneMaxNs=Math.max(phoneMaxNs,age);freshCount++;
                    }
                }
                now=SystemClock.elapsedRealtime();
                long fresh=source.freshFrames();sourceFrames=Math.max(sourceFrames,fresh<0?submitted:fresh);
                if(kcpRef!=null && video!=null) {
                    for(int n=0;n<16;n++) {
                        DatagramPacket ack=new DatagramPacket(new byte[2048],2048);
                        try{video.receive(ack);}catch(SocketTimeoutException e){break;}
                        kcpRef.input(Arrays.copyOf(ack.getData(),ack.getLength()));
                    }
                    kcpRef.update(now);
                    if(kcpRef.getState()<0)throw new IOException("七寸KCP连接失效，请停止后重新连接仪表");
                }
                if(control!=null) {
                    if(now>=nextBeat) {
                        byte[] beat=NinebotProtocol.heartbeat(++counter);control.send(new DatagramPacket(beat,beat.length));
                        probes.put(counter,now);while(probes.size()>8)probes.remove(probes.keySet().iterator().next());nextBeat=now+1000;
                    }
                    for(int n=0;n<8;n++) {
                        DatagramPacket response=new DatagramPacket(new byte[2048],2048);
                        try{control.receive(response);}catch(SocketTimeoutException e){break;}
                        byte[] p=Arrays.copyOf(response.getData(),response.getLength());int c=NinebotProtocol.heartbeatReply(p);
                        Long sent=probes.remove(c);
                        if(sent!=null){replies++;lastReply=now;listener.log("RTCP #"+c+" 回应，往返 "+(SystemClock.elapsedRealtime()-sent)+"ms");}
                        else listener.log("RTCP RX "+p.length+"B "+NinebotProtocol.hex(p,48));
                    }
                }
                if(now>=nextStats) {
                    long elapsed=Math.max(1,now-lastStatsAt);
                    String rates=String.format(Locale.US,"来源 %.1f FPS · 编码 %.1f FPS",(sourceFrames-lastSourceFrames)*1000.0/elapsed,(frames-lastEncodedFrames)*1000.0/elapsed);
                    listener.stats((self?"本机自检":"发送中")+" · "+rates+"\n累计编码 "+frames+" 帧 · "+(tcpPull?"TCP":"RTP")+" "+packets[0]+" 包 / "+bytes[0]/1024+" KiB\n"+
                        (self?"未发送网络数据":tcpPull?"仪表已请求 "+packets[0]+" 帧": "仪表 RTCP 回应 "+replies+" 次"+(lastReply==0?"；尚无回应":"；距上次 "+(now-lastReply)/1000+" 秒")));
                    listener.log("FPS："+rates+"，目标 "+activeProfile.fps()+" FPS");
                    if(kcpRef!=null)listener.log("KCP待确认/排队："+kcpRef.waitSnd()+"段");
                    listener.log(String.format(Locale.US,"手机耗时：画面合成+输入准备 %.1f ms/帧，编码提交至输出 %.1f ms/帧，待编码 %d 帧（非端到端延迟）",
                        conversionNs/1e6/Math.max(1,conversionCount),codecNs/1e6/Math.max(1,codecCount),inFlight.size()));
                    if(freshCount>0)listener.log(String.format(Locale.US,"新来源帧：采集转换 %.1f ms，等候编码 %.1f ms，处理开始至发送返回 %.1f / 最大 %.1f ms（%d帧；不含仪表显示/空口排队）",
                        sourceNs/1e6/freshCount,busNs/1e6/freshCount,phoneNs/1e6/freshCount,phoneMaxNs/1e6,freshCount));
                    listener.log(String.format(Locale.US,"封包/发送：平均 %.1f / 最大 %.1f ms/帧",sendNs/1e6/Math.max(1,sendCount),sendMaxNs/1e6));
                    listener.log(String.format(Locale.US,"节奏：来源最大间隔 %.1f ms，输出最大间隔 %.1f ms，输入最长 %.1f ms，最大压缩帧 %.1f KiB，关键帧 %d（静止补帧间隔会变长）",
                        source.maxReadyGapNs()/1e6,outputGapMaxNs/1e6,inputMaxNs/1e6,maxFrameBytes/1024.0,keyCount));
                    inputMaxNs=0;outputGapMaxNs=0;keyCount=0;maxFrameBytes=0;
                    sourceNs=0;busNs=0;phoneNs=0;phoneMaxNs=0;freshCount=0;sendNs=0;sendMaxNs=0;sendCount=0;
                    conversionNs=0;conversionCount=0;codecNs=0;codecCount=0;
                    lastStatsAt=now;lastSourceFrames=sourceFrames;lastEncodedFrames=frames;
                    nextStats=now+1000;
                }
                LockSupport.parkNanos(1000000);
            }
            listener.log((self?"SELF_TEST_OK":"发送结束")+"：来源更新 "+sourceFrames+" 帧，编码 "+frames+" 帧，封包 "+packets[0]+" 个"+(tcpPull?"（TCP）":"，RTCP 回应 "+replies+" 次")+
                (capture==null?"":"，本机视频="+capture.getName()));
        } catch(Exception e) {if(!stopped)listener.log("会话失败："+e.getClass().getSimpleName()+" / "+e.getMessage());}
        finally {
            if(ec!=null){ec.finishMedia();ec.close();easyConn=null;}
            if(pxc!=null){pxc.finishMedia();pxc.close();tailing=null;}
            if(control!=null) {try {byte[] b=NinebotProtocol.bye();control.send(new DatagramPacket(b,b.length));}catch(Exception ignored){} control.close();control=null;}
            if(video!=null){video.close();video=null;}
            if(codec!=null){try{codec.stop();}catch(Exception ignored){}codec.release();}
            if(gpu!=null)gpu.close();
            if(bitmap!=null)bitmap.recycle();if(saved!=null)try{saved.close();}catch(IOException ignored){}
            source.close();
            listener.finished();
        }
    }
    private void runJpeg() {
        Bitmap bitmap=null;FileOutputStream saved=null;
        NinebotJpegProtocol protocol=new NinebotJpegProtocol(new java.security.SecureRandom().nextInt());
        long timestampBase=new java.security.SecureRandom().nextInt()&0xffffffffL,lastTimestamp=timestampBase;
        long[] packetCount={0},byteCount={0};int frames=0,replies=0,counter=0;
        boolean self=route==null;
        try {
            if(!self){video=socket(5012);control=socket(5013);}
            if(capture!=null)saved=new FileOutputStream(capture);
            bitmap=Bitmap.createBitmap(profile.width(),profile.height(),Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(bitmap);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
            ByteArrayOutputStream encoded=new ByteArrayOutputStream(32768);
            long began=SystemClock.elapsedRealtime(),nextFrame=began,nextBeat=began+1000,nextStats=began+1000;
            long priorFrames=0,priorAt=began;Map<Integer,Long> probes=new LinkedHashMap<>();
            listener.log("JPEG/RTP："+profile.width()+"×"+profile.height()+" / "+profile.fps()+"FPS / 质量"+profile.jpegQuality()+"；1400字节分片，不使用H.264关键帧重复");
            if(!self)listener.log("UDP "+route.local().getHostAddress()+":5012/5013 → "+route.remote().getHostAddress()+"（已验证P2P路由）");
            while(!stopped && (!self || SystemClock.elapsedRealtime()-began<3000)) {
                long now=SystemClock.elapsedRealtime();
                if(now>=nextFrame) {
                    if(source.draw(canvas,paint,frames)) {
                        encoded.reset();if(!bitmap.compress(Bitmap.CompressFormat.JPEG,profile.jpegQuality(),encoded))throw new IOException("JPEG编码失败");
                        byte[] data=encoded.toByteArray();lastTimestamp=(timestampBase+now-began)&0xffffffffL;
                        if(saved!=null)saved.write(data);
                        protocol.sendFrame(data,lastTimestamp,p->{
                            if(stopped)throw new InterruptedIOException("已停止");
                            if(video!=null)video.send(new DatagramPacket(p,p.length));packetCount[0]++;byteCount[0]+=p.length;
                        });frames++;source.submitted();
                    }
                    nextFrame=FramePacing.nextDeadline(SystemClock.elapsedRealtime(),nextFrame,profile.fps());
                }
                now=SystemClock.elapsedRealtime();
                if(control!=null) {
                    if(now>=nextBeat) {
                        int id=counter++;byte[] p=NinebotJpegProtocol.heartbeat(id);control.send(new DatagramPacket(p,p.length));
                        probes.put(id,now);while(probes.size()>8)probes.remove(probes.keySet().iterator().next());nextBeat=now+1000;
                    }
                    for(int n=0;n<8;n++) {
                        DatagramPacket response=new DatagramPacket(new byte[2048],2048);
                        try{control.receive(response);}catch(SocketTimeoutException e){break;}
                        byte[] p=Arrays.copyOf(response.getData(),response.getLength());int id=NinebotProtocol.heartbeatReply(p);
                        Long sent=probes.remove(id);
                        if(sent!=null){replies++;listener.log("JPEG RTCP #"+id+" 回应，往返 "+(SystemClock.elapsedRealtime()-sent)+"ms");}
                        else listener.log("JPEG RTCP RX "+p.length+"B "+NinebotProtocol.hex(p,48));
                    }
                }
                if(now>=nextStats) {
                    long fresh=source.freshFrames();
                    listener.stats((self?"本机自检":"JPEG发送中")+String.format(Locale.US," · %.1f FPS",(frames-priorFrames)*1000.0/Math.max(1,now-priorAt))+"\nJPEG "+frames+"帧 · RTP "+packetCount[0]+"包 / "+byteCount[0]/1024+"KiB · RTCP回应 "+replies+"次");
                    listener.log("JPEG统计：来源 "+(fresh<0?frames:fresh)+" 帧，编码 "+frames+" 帧，发送 "+packetCount[0]+" 包，回应 "+replies+" 次");
                    priorFrames=frames;priorAt=now;nextStats=now+1000;
                }
                LockSupport.parkNanos(1000000);
            }
            listener.log((self?"JPEG_SELF_TEST_OK":"JPEG发送结束")+"：编码 "+frames+" 帧，RTP "+packetCount[0]+" 包，RTCP回应 "+replies+" 次"+(capture==null?"":"，本机文件="+capture.getName()));
        }catch(Exception e){if(!stopped)listener.log("JPEG会话失败："+e.getClass().getSimpleName()+" / "+e.getMessage());}
        finally {
            if(control!=null){try{byte[] p=protocol.bye(lastTimestamp,System.currentTimeMillis());control.send(new DatagramPacket(p,p.length));}catch(Exception ignored){}control.close();control=null;}
            if(video!=null){video.close();video=null;}if(bitmap!=null)bitmap.recycle();
            if(saved!=null)try{saved.close();}catch(IOException ignored){}source.close();listener.finished();
        }
    }
    private void runTailing() {
        Bitmap bitmap=null;TailingConnection connection=new TailingConnection(context,route,profile,listener);tailing=connection;
        try {
            if(stopped)return;
            connection.start();if(stopped)return;
            bitmap=Bitmap.createBitmap(profile.width(),profile.height(),Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(bitmap);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
            ByteArrayOutputStream encoded=new ByteArrayOutputStream(32768);
            int frames=0,previous=0;long now=SystemClock.elapsedRealtime(),next=now,at=now,nextLog=now+5000;
            while(!stopped) {
                now=SystemClock.elapsedRealtime();
                if(now>=next) {
                    if(source.draw(canvas,paint,frames)) {
                        encoded.reset();if(!bitmap.compress(Bitmap.CompressFormat.JPEG,profile.jpegQuality(),encoded))throw new IOException("JPEG编码失败");
                        connection.send(encoded.toByteArray());frames++;source.submitted();
                    }
                    next=FramePacing.nextDeadline(SystemClock.elapsedRealtime(),next,profile.fps());
                }
                if(now-at>=1000) {
                    listener.stats(String.format(Locale.US,"台铃会话已就绪 · %.1f FPS\nJPEG %d 帧 · 仪表反馈 %d 次",(frames-previous)*1000.0/(now-at),frames,frames));
                    previous=frames;at=now;
                }
                if(now>=nextLog){listener.log("台铃JPEG累计发送 "+frames+" 帧，仪表有效反馈 "+frames+" 次");nextLog=now+5000;}
                LockSupport.parkNanos(1000000);
            }
        }catch(Exception e){if(!stopped)listener.log("台铃投屏失败："+e.getClass().getSimpleName()+" / "+e.getMessage());}
        finally{connection.close();tailing=null;if(bitmap!=null)bitmap.recycle();source.close();listener.finished();}
    }
    private static void writeYuv(Image image,int[] rgb,int width,int height,byte[] yRow) {
        Image.Plane[] planes=image.getPlanes();
        if(planes.length!=3)throw new IllegalStateException("不是三平面 YUV420 图像");
        ByteBuffer y=planes[0].getBuffer(),u=planes[1].getBuffer(),v=planes[2].getBuffer();
        int y0=y.position(),u0=u.position(),v0=v.position();
        int ys=planes[0].getRowStride(),yp=planes[0].getPixelStride();
        int us=planes[1].getRowStride(),up=planes[1].getPixelStride();
        int vs=planes[2].getRowStride(),vp=planes[2].getPixelStride();
        for(int row=0;row<height;row++) {
          for(int col=0;col<width;col++) {
            int color=rgb[row*width+col],r=(color>>16)&255,g=(color>>8)&255,b=color&255;
            byte luma=(byte)clamp(((66*r+129*g+25*b+128)>>8)+16);
            if(yp==1)yRow[col]=luma;else y.put(y0+row*ys+col*yp,luma);
            if((row&1)==0 && (col&1)==0) {
                int ar=0,ag=0,ab=0;
                for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++){int p=rgb[(row+dy)*width+col+dx];ar+=(p>>16)&255;ag+=(p>>8)&255;ab+=p&255;}
                ar/=4;ag/=4;ab/=4;
                u.put(u0+(row/2)*us+(col/2)*up,(byte)clamp(((-38*ar-74*ag+112*ab+128)>>8)+128));
                v.put(v0+(row/2)*vs+(col/2)*vp,(byte)clamp(((112*ar-94*ag-18*ab+128)>>8)+128));
            }
          }
          if(yp==1){y.position(y0+row*ys);y.put(yRow,0,width);}
        }
    }
    private static int clamp(int n){return Math.max(0,Math.min(255,n));}
}
