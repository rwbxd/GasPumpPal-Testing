package com.rwbdev.prototest

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

object FillupSerializer : Serializer<Fillups> {
    override val defaultValue: Fillups = Fillups.getDefaultInstance()
    override suspend fun readFrom(input: InputStream): Fillups {
        try {
            return Fillups.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", exception)
        }
    }

    override suspend fun writeTo(t: Fillups, output: OutputStream) {
        t.writeTo(output)
    }
}