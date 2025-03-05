import com.spruceid.mobile.sdk.rs.*;

assert(terminateSession().contentEquals(
            listOf(0xa1, 0x66, 0x73, 0x74, 0x61, 0x74, 0x75, 0x73, 0x14)
            .map { it.toByte() }
            .toByteArray()
        )
      )
