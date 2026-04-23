package com.example.trafykamerasikotlin.data.vision

/**
 * Vehicle / road-agent classes we care about from the COCO taxonomy. Indices
 * must stay in lockstep with `scripts/export-yolo-ncnn.py`'s COCO_CLASSES list
 * so the flat float array coming out of JNI maps correctly.
 */
enum class VehicleClass(val cocoIndex: Int, val labelKey: String) {
    PERSON(0,       "vehicle_class_person"),
    BICYCLE(1,      "vehicle_class_bicycle"),
    CAR(2,          "vehicle_class_car"),
    MOTORCYCLE(3,   "vehicle_class_motorcycle"),
    BUS(5,          "vehicle_class_bus"),
    TRUCK(7,        "vehicle_class_truck"),
    UNKNOWN(-1,     "vehicle_class_unknown");

    companion object {
        private val byIndex: Map<Int, VehicleClass> =
            entries.filter { it.cocoIndex >= 0 }.associateBy { it.cocoIndex }

        fun fromCocoIndex(idx: Int): VehicleClass = byIndex[idx] ?: UNKNOWN

        /**
         * Whitelist used by [NcnnVehicleDetector] to drop detections that
         * aren't road agents. Explicit rather than "all non-UNKNOWN" so we
         * can evolve the enum without breaking the filter semantics.
         */
        val WHITELIST: Set<VehicleClass> =
            setOf(PERSON, BICYCLE, CAR, MOTORCYCLE, BUS, TRUCK)
    }
}
