package com.musicfind.app.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Numeric serializers that never throw: if the server sends a number as a string
 * (or a junk string), they fall back to the default instead of crashing with
 * "For input string".
 */
private fun JsonPrimitive.intSafe(): Int? =
    intOrNull ?: content.trim().takeWhile { it.isDigit() || it == '-' }.toIntOrNull()

private fun JsonPrimitive.longSafe(): Long? =
    longOrNull ?: content.trim().takeWhile { it.isDigit() || it == '-' }.toLongOrNull()

private fun JsonPrimitive.doubleSafe(): Double? =
    doubleOrNull ?: content.trim().takeWhile { it.isDigit() || it == '-' || it == '.' }.toDoubleOrNull()

object LenientInt : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientInt", PrimitiveKind.INT)
    override fun deserialize(decoder: Decoder): Int {
        val json = decoder as? JsonDecoder ?: return decoder.decodeInt()
        return (json.decodeJsonElement() as? JsonPrimitive)?.intSafe() ?: 0
    }
    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

object LenientNullableInt : KSerializer<Int?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientNullableInt", PrimitiveKind.INT)
    override fun deserialize(decoder: Decoder): Int? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeInt()
        return (json.decodeJsonElement() as? JsonPrimitive)?.intSafe()
    }
    override fun serialize(encoder: Encoder, value: Int?) {
        if (value != null) encoder.encodeInt(value) else encoder.encodeNull()
    }
}

object LenientLong : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientLong", PrimitiveKind.LONG)
    override fun deserialize(decoder: Decoder): Long {
        val json = decoder as? JsonDecoder ?: return decoder.decodeLong()
        return (json.decodeJsonElement() as? JsonPrimitive)?.longSafe() ?: 0L
    }
    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
}

object LenientNullableLong : KSerializer<Long?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientNullableLong", PrimitiveKind.LONG)
    override fun deserialize(decoder: Decoder): Long? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeLong()
        return (json.decodeJsonElement() as? JsonPrimitive)?.longSafe()
    }
    override fun serialize(encoder: Encoder, value: Long?) {
        if (value != null) encoder.encodeLong(value) else encoder.encodeNull()
    }
}

object LenientDouble : KSerializer<Double> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientDouble", PrimitiveKind.DOUBLE)
    override fun deserialize(decoder: Decoder): Double {
        val json = decoder as? JsonDecoder ?: return decoder.decodeDouble()
        return (json.decodeJsonElement() as? JsonPrimitive)?.doubleSafe() ?: 0.0
    }
    override fun serialize(encoder: Encoder, value: Double) = encoder.encodeDouble(value)
}

object LenientNullableDouble : KSerializer<Double?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientNullableDouble", PrimitiveKind.DOUBLE)
    override fun deserialize(decoder: Decoder): Double? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeDouble()
        return (json.decodeJsonElement() as? JsonPrimitive)?.doubleSafe()
    }
    override fun serialize(encoder: Encoder, value: Double?) {
        if (value != null) encoder.encodeDouble(value) else encoder.encodeNull()
    }
}
