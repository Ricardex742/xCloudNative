package com.xcloud.tclnative.webrtc

import com.xcloud.tclnative.i18n.UiText
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Counters and header flags only. Never retain pointer coordinates, packet bodies or identities. */
class RemoteInputTrace {
    private var received=0L;private var metadata=0L;private var ignored=0L
    private var lastFlags: Int?=null;private var lastSize=0
    @Synchronized fun observe(data: ByteBuffer,report: RemoteInputReport?) {
        received++;lastSize=data.remaining()
        lastFlags=if(lastSize>=2) data.slice().order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 65535 else null
        if(report?.width!=null && report.height!=null) metadata++
        if(report==null) ignored++
    }
    @Synchronized fun diagnostics(version: Int)=UiText.text("Input remoto: versão {0}; pacotes {1}; metadata válida {2}; ignorados {3}; último tamanho {4} B; flags {5}",
        UiText.nested(version),UiText.nested(received),UiText.nested(metadata),UiText.nested(ignored),UiText.nested(lastSize),
        UiText.nested(lastFlags?.let { "0x"+it.toString(16) } ?: "n/d"))
}
