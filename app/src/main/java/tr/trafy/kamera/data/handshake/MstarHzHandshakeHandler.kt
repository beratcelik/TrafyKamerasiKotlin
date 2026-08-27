package tr.trafy.kamera.data.handshake

import tr.trafy.kamera.data.model.ChipsetProtocol

/**
 * MStar HZ (Hezheng variant) — identical handshake to MStar but at 192.72.1.1.
 * The only difference is the device IP, which comes from the ChipsetProtocol enum.
 *
 * Reference: MstartHzDvrProtocol.java — currentIp = "192.72.1.1".
 */
class MstarHzHandshakeHandler : MstarHandshakeHandler(ChipsetProtocol.MSTAR_HZ)
