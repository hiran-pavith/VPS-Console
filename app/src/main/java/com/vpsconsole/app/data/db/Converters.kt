package com.vpsconsole.app.data.db

import androidx.room.TypeConverter
import com.vpsconsole.app.data.model.AuthType
import com.vpsconsole.app.data.model.MetricKind
import com.vpsconsole.app.data.model.TunnelType

class Converters {
    @TypeConverter
    fun authTypeToString(value: AuthType): String = value.name

    @TypeConverter
    fun stringToAuthType(value: String): AuthType =
        runCatching { AuthType.valueOf(value) }.getOrDefault(AuthType.KEY)

    @TypeConverter
    fun metricKindToString(value: MetricKind): String = value.name

    @TypeConverter
    fun stringToMetricKind(value: String): MetricKind =
        runCatching { MetricKind.valueOf(value) }.getOrDefault(MetricKind.CPU)

    @TypeConverter
    fun tunnelTypeToString(value: TunnelType): String = value.name

    @TypeConverter
    fun stringToTunnelType(value: String): TunnelType =
        runCatching { TunnelType.valueOf(value) }.getOrDefault(TunnelType.LOCAL)
}
