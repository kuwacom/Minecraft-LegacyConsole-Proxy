package dev.kuwa.mlcproxy.bridge

import java.util.concurrent.ConcurrentHashMap

class IdMapStore {
    private val mlcToJavaEntityId = ConcurrentHashMap<Int, Int>()
    private val javaToMlcEntityId = ConcurrentHashMap<Int, Int>()

    fun putEntityId(mlcId: Int, javaId: Int) {
        mlcToJavaEntityId[mlcId] = javaId
        javaToMlcEntityId[javaId] = mlcId
    }

    fun getJavaEntityId(mlcId: Int): Int? = mlcToJavaEntityId[mlcId]

    fun getMlcEntityId(javaId: Int): Int? = javaToMlcEntityId[javaId]

    fun clear() {
        mlcToJavaEntityId.clear()
        javaToMlcEntityId.clear()
    }
}
