/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */
/* 세션을 유지하기 위해 서버와 피어간 연결을 관리하는 데 사용되는 앱의 핵심 클래스
*  PeerConnection을 초기화하고, 서버에서 로컬 오디오 및 비디오 스트림을 유지하게 도움. */
package com.example.webrtc_kotlin_sample2

import android.app.Application
import android.content.Context
import android.util.Log
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import org.webrtc.*


class RTCClient(
        context: Application,
        observer: PeerConnection.Observer
) {

    companion object {
        private const val LOCAL_TRACK_ID = "local_track"
        private const val LOCAL_STREAM_ID = "local_track"
    }

    private val rootEglBase: EglBase = EglBase.create()

    private var localAudioTrack : AudioTrack? = null
    private var localVideoTrack : VideoTrack? = null
    val TAG = "RTCClient"

    var remoteSessionDescription : SessionDescription? = null

    /* Database로 Firebase를 사용하는데 firestore의 형태로 사용한다.*/
    val db = Firebase.firestore

    init {
        initPeerConnectionFactory(context)
    }

    private val iceServer = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                    .createIceServer()
    )

    private val peerConnectionFactory by lazy { buildPeerConnectionFactory() }
    private val videoCapturer by lazy { getVideoCapturer(context) }

    private val audioSource by lazy { peerConnectionFactory.createAudioSource(MediaConstraints())}
    private val localVideoSource by lazy { peerConnectionFactory.createVideoSource(false) }
    private val peerConnection by lazy { buildPeerConnection(observer) }

    private fun initPeerConnectionFactory(context: Application) {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
    }

    private fun buildPeerConnectionFactory(): PeerConnectionFactory {
        return PeerConnectionFactory
                .builder()
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(rootEglBase.eglBaseContext))
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true))
                .setOptions(PeerConnectionFactory.Options().apply {
                    disableEncryption = true
                    disableNetworkMonitor = true
                })
                .createPeerConnectionFactory()
    }

    private fun buildPeerConnection(observer: PeerConnection.Observer) = peerConnectionFactory.createPeerConnection(
            iceServer,
            observer
    )

    private fun getVideoCapturer(context: Context) =
            Camera2Enumerator(context).run {
                deviceNames.find {
                    isFrontFacing(it)
                }?.let {
                    createCapturer(it, null)
                } ?: throw IllegalStateException()
            }

    fun initSurfaceView(view: SurfaceViewRenderer) = view.run {
        setMirror(true)
        setEnableHardwareScaler(true)
        init(rootEglBase.eglBaseContext, null)
    }

    fun startLocalVideoCapture(localVideoOutput: SurfaceViewRenderer) {
        val surfaceTextureHelper = SurfaceTextureHelper.create(Thread.currentThread().name, rootEglBase.eglBaseContext)
        (videoCapturer as VideoCapturer).initialize(surfaceTextureHelper, localVideoOutput.context, localVideoSource.capturerObserver)
        videoCapturer.startCapture(320, 240, 60)
        localAudioTrack = peerConnectionFactory.createAudioTrack(LOCAL_TRACK_ID + "_audio", audioSource);
        localVideoTrack = peerConnectionFactory.createVideoTrack(LOCAL_TRACK_ID, localVideoSource)
        localVideoTrack?.addSink(localVideoOutput)
        val localStream = peerConnectionFactory.createLocalMediaStream(LOCAL_STREAM_ID)
        localStream.addTrack(localVideoTrack)
        localStream.addTrack(localAudioTrack)
        peerConnection?.addStream(localStream)
    }

    /* PeerConnection.call 메서드는 createOffer()메서드를 사용하여 통화 서비스를 시작하는 데 사용*/
    private fun PeerConnection.call(sdpObserver: SdpObserver, meetingID: String) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        /*Call 서비스를 시작.
         createOFfer()메서드 성공시 Firestore에서 offer유형으로 sdp 푸시.*/
        createOffer(object : SdpObserver by sdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                setLocalDescription(object : SdpObserver {
                    override fun onSetFailure(p0: String?) {
                        Log.e(TAG, "onSetFailure: $p0")
                    }

                    override fun onSetSuccess() {
                        val offer = hashMapOf(
                                "sdp" to desc?.description,
                                "type" to desc?.type
                        )
                        db.collection("calls").document(meetingID)
                                .set(offer)
                                .addOnSuccessListener {
                                    Log.e(TAG, "DocumentSnapshot added")
                                }
                                .addOnFailureListener { e ->
                                    Log.e(TAG, "Error adding document", e)
                                }
                        Log.e(TAG, "onSetSuccess")
                    }

                    override fun onCreateSuccess(p0: SessionDescription?) {
                        Log.e(TAG, "onCreateSuccess: Description $p0")
                    }

                    override fun onCreateFailure(p0: String?) {
                        Log.e(TAG, "onCreateFailure: $p0")
                    }
                }, desc)
                sdpObserver.onCreateSuccess(desc)
            }

            override fun onSetFailure(p0: String?) {
                Log.e(TAG, "onSetFailure: $p0")
            }

            override fun onCreateFailure(p0: String?) {
                Log.e(TAG, "onCreateFailure: $p0")
            }
        }, constraints)
    }

    /* PeerConnection.answer() 메서드는
    createAnswer()메서드를 사용하여 제안에 대한 응답을 제공하는데 사용됩니다. */
    private fun PeerConnection.answer(sdpObserver: SdpObserver, meetingID: String) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        /* 사용자가 오퍼를 받을 때마다 트리거되고
        createAnswer()가 성공하면 Firestore에서 Answer 유형으로 SDP를 업데이트 합니다. */
        createAnswer(object : SdpObserver by sdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                val answer = hashMapOf(
                        "sdp" to desc?.description,
                        "type" to desc?.type
                )
                db.collection("calls").document(meetingID)
                        .set(answer)
                        .addOnSuccessListener {
                            Log.e(TAG, "DocumentSnapshot added")
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Error adding document", e)
                        }
                setLocalDescription(object : SdpObserver {
                    override fun onSetFailure(p0: String?) {
                        Log.e(TAG, "onSetFailure: $p0")
                    }

                    override fun onSetSuccess() {
                        Log.e(TAG, "onSetSuccess")
                    }

                    override fun onCreateSuccess(p0: SessionDescription?) {
                        Log.e(TAG, "onCreateSuccess: Description $p0")
                    }

                    override fun onCreateFailure(p0: String?) {
                        Log.e(TAG, "onCreateFailureLocal: $p0")
                    }
                }, desc)
                sdpObserver.onCreateSuccess(desc)
            }

            override fun onCreateFailure(p0: String?) {
                Log.e(TAG, "onCreateFailureRemote: $p0")
            }
        }, constraints)
    }

    fun call(sdpObserver: SdpObserver, meetingID: String) = peerConnection?.call(sdpObserver, meetingID)

    fun answer(sdpObserver: SdpObserver, meetingID: String) = peerConnection?.answer(sdpObserver, meetingID)

    /* PeerConnectiono에 원격 세션을 추가하여 연결을 설정하는 데 사용된다.
    * 따라서 call() 및 answer()메서드 아래에 추가됩니다. */
    fun onRemoteSessionReceived(sessionDescription: SessionDescription) {
        remoteSessionDescription = sessionDescription
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetFailure(p0: String?) {
                Log.e(TAG, "onSetFailure: $p0")
            }

            override fun onSetSuccess() {
                Log.e(TAG, "onSetSuccessRemoteSession")
            }

            override fun onCreateSuccess(p0: SessionDescription?) {
                Log.e(TAG, "onCreateSuccessRemoteSession: Description $p0")
            }

            override fun onCreateFailure(p0: String?) {
                Log.e(TAG, "onCreateFailure")
            }
        }, sessionDescription)

    }

    /* addIceCandidate() 메서드는 연결에 후보를 추가하는데 사용됩니다.
    *  offerCandidate 및 answerCandidate 둘 다에 대해 트리거가 되므로 서로 통신 할 수 있습니다. */
    fun addIceCandidate(iceCandidate: IceCandidate?) {
        peerConnection?.addIceCandidate(iceCandidate)
    }

    fun endCall(meetingID: String) {
        db.collection("calls").document(meetingID).collection("candidates")
                .get().addOnSuccessListener {
                    val iceCandidateArray: MutableList<IceCandidate> = mutableListOf()
                    for ( dataSnapshot in it) {
                        if (dataSnapshot.contains("type") && dataSnapshot["type"]=="offerCandidate") {
                            val offerCandidate = dataSnapshot
                            iceCandidateArray.add(IceCandidate(offerCandidate["sdpMid"].toString(), Math.toIntExact(offerCandidate["sdpMLineIndex"] as Long), offerCandidate["sdp"].toString()))
                        } else if (dataSnapshot.contains("type") && dataSnapshot["type"]=="answerCandidate") {
                            val answerCandidate = dataSnapshot
                            iceCandidateArray.add(IceCandidate(answerCandidate["sdpMid"].toString(), Math.toIntExact(answerCandidate["sdpMLineIndex"] as Long), answerCandidate["sdp"].toString()))
                        }
                    }
                    peerConnection?.removeIceCandidates(iceCandidateArray.toTypedArray())
                }
        val endCall = hashMapOf(
                "type" to "END_CALL"
        )
        db.collection("calls").document(meetingID)
                .set(endCall)
                .addOnSuccessListener {
                    Log.e(TAG, "DocumentSnapshot added")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Error adding document", e)
                }

        peerConnection?.close()
    }

    fun enableVideo(videoEnabled: Boolean) {
        if (localVideoTrack !=null)
            localVideoTrack?.setEnabled(videoEnabled)
    }

    fun enableAudio(audioEnabled: Boolean) {
        if (localAudioTrack != null)
            localAudioTrack?.setEnabled(audioEnabled)
    }
    fun switchCamera() {
        videoCapturer.switchCamera(null)
    }
}