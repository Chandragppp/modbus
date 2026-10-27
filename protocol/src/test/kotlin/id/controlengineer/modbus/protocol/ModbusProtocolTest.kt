package id.controlengineer.modbus.protocol

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModbusProtocolTest {

    /**
     * Vektor uji CRC-16/MODBUS dari sumber standar:
     * 1) MODBUS over Serial Line Specification and Implementation Guide V1.02, §6.2.2 (hlm. 41):
     *    Frame 0x02, 0x07 -> CRC register = 0x1241, dikirim low-byte lebih dulu: [0x41, 0x12].
     * 2) Katalog CRC-16/MODBUS (poly=0x8005, init=0xFFFF, refin=true, refout=true, xorout=0x0000):
     *    ASCII "123456789" -> 0x4B37.
     * 3) Contoh frame RTU standar FC03 & Exception:
     *    - 01 03 00 00 00 0A -> CRC = 0xCDC5, wire = [0xC5, 0xCD]
     *    - 01 03 00 6B 00 03 -> CRC = 0x1774, wire = [0x74, 0x17]
     *    - 01 84 06 (Exception Slave Busy) -> CRC = 0x02C3, wire = [0xC3, 0x02]
     */
    @Test
    fun crc16ModbusSpecificationVectors() {
        val specExample = byteArrayOf(0x02, 0x07)
        assertEquals(0x1241, ModbusCrc16.compute(specExample))
        val specWithCrc = ModbusCrc16.append(specExample)
        assertContentEquals(
            byteArrayOf(0x02, 0x07, 0x41, 0x12),
            specWithCrc,
        )
        assertTrue(ModbusCrc16.verify(specWithCrc))

        val checkAscii = "123456789".encodeToByteArray()
        assertEquals(0x4B37, ModbusCrc16.compute(checkAscii))

        val fc03TenRegs = HexFormatUtil.fromHexString("01 03 00 00 00 0A")
        assertEquals(0xCDC5, ModbusCrc16.compute(fc03TenRegs))
        assertEquals("01 03 00 00 00 0A C5 CD", HexFormatUtil.toHexString(ModbusCrc16.append(fc03TenRegs)))

        val fc03Spec = HexFormatUtil.fromHexString("01 03 00 6B 00 03")
        assertEquals(0x1774, ModbusCrc16.compute(fc03Spec))
        assertEquals("01 03 00 6B 00 03 74 17", HexFormatUtil.toHexString(ModbusCrc16.append(fc03Spec)))

        val exBusy = HexFormatUtil.fromHexString("01 84 06")
        assertEquals(0x02C3, ModbusCrc16.compute(exBusy))
        assertEquals("01 84 06 C3 02", HexFormatUtil.toHexString(ModbusCrc16.append(exBusy)))
    }

    /**
     * Uji encode/decode seluruh Function Code berdasarkan contoh PDU di
     * MODBUS Application Protocol Specification V1.1b3 §6.1 s/d §6.12.
     */
    @Test
    fun encodeAndDecodeAllFunctionCodes() {
        // FC01 (§6.1): Read Coils start=0x0013 (19), count=0x0013 (19)
        val req01 = ModbusRequest.ReadCoils(startAddress = 0x0013, count = 19)
        val pdu01 = ModbusPduCodec.encodeRequest(req01)
        assertEquals("01 00 13 00 13", HexFormatUtil.toHexString(pdu01))
        assertEquals(req01, ModbusPduCodec.decodeRequest(pdu01))
        val resp01Pdu = HexFormatUtil.fromHexString("01 03 CD 6B 05")
        val resp01 = assertIs<ModbusResponse.ReadBits>(ModbusPduCodec.decodeResponse(resp01Pdu, expectedCount = 19))
        assertEquals(19, resp01.bits.size)
        assertTrue(resp01.bits[0]) // 0xCD = 1100 1101 -> bit 0 = 1, bit 1 = 0, bit 2 = 1
        assertFalse(resp01.bits[1])
        assertTrue(resp01.bits[2])
        assertEquals("01 03 CD 6B 05", HexFormatUtil.toHexString(ModbusPduCodec.encodeResponse(resp01)))

        // FC02 (§6.2): Read Discrete Inputs start=0x00C4 (196), count=0x0016 (22)
        val req02 = ModbusRequest.ReadDiscreteInputs(startAddress = 0x00C4, count = 22)
        val pdu02 = ModbusPduCodec.encodeRequest(req02)
        assertEquals("02 00 C4 00 16", HexFormatUtil.toHexString(pdu02))
        assertEquals(req02, ModbusPduCodec.decodeRequest(pdu02))
        val resp02Pdu = HexFormatUtil.fromHexString("02 03 AC DB 35")
        val resp02 = assertIs<ModbusResponse.ReadBits>(ModbusPduCodec.decodeResponse(resp02Pdu, expectedCount = 22))
        assertEquals(22, resp02.bits.size)
        assertEquals("02 03 AC DB 35", HexFormatUtil.toHexString(ModbusPduCodec.encodeResponse(resp02)))

        // FC03 (§6.3): Read Holding Registers start=0x006B (107), count=3
        val req03 = ModbusRequest.ReadHoldingRegisters(startAddress = 0x006B, count = 3)
        val pdu03 = ModbusPduCodec.encodeRequest(req03)
        assertEquals("03 00 6B 00 03", HexFormatUtil.toHexString(pdu03))
        assertEquals(req03, ModbusPduCodec.decodeRequest(pdu03))
        val resp03Pdu = HexFormatUtil.fromHexString("03 06 02 2B 00 00 00 64")
        val resp03 = assertIs<ModbusResponse.ReadRegisters>(ModbusPduCodec.decodeResponse(resp03Pdu, expectedCount = 3))
        assertEquals(listOf(0x022B, 0x0000, 0x0064), resp03.registers)
        assertEquals("03 06 02 2B 00 00 00 64", HexFormatUtil.toHexString(ModbusPduCodec.encodeResponse(resp03)))

        // FC04 (§6.4): Read Input Registers start=0x0008 (8), count=1
        val req04 = ModbusRequest.ReadInputRegisters(startAddress = 0x0008, count = 1)
        val pdu04 = ModbusPduCodec.encodeRequest(req04)
        assertEquals("04 00 08 00 01", HexFormatUtil.toHexString(pdu04))
        assertEquals(req04, ModbusPduCodec.decodeRequest(pdu04))
        val resp04Pdu = HexFormatUtil.fromHexString("04 02 00 0A")
        val resp04 = assertIs<ModbusResponse.ReadRegisters>(ModbusPduCodec.decodeResponse(resp04Pdu, expectedCount = 1))
        assertEquals(listOf(10), resp04.registers)

        // FC05 (§6.5): Write Single Coil address=0x00AC (172), ON (0xFF00)
        val req05 = ModbusRequest.WriteSingleCoil(startAddress = 0x00AC, value = true)
        val pdu05 = ModbusPduCodec.encodeRequest(req05)
        assertEquals("05 00 AC FF 00", HexFormatUtil.toHexString(pdu05))
        assertEquals(req05, ModbusPduCodec.decodeRequest(pdu05))
        val resp05 = assertIs<ModbusResponse.WriteSingleCoil>(ModbusPduCodec.decodeResponse(pdu05))
        assertEquals(0x00AC, resp05.address)
        assertTrue(resp05.value)

        // FC06 (§6.6): Write Single Register address=0x0001, value=0x0003
        val req06 = ModbusRequest.WriteSingleRegister(startAddress = 0x0001, value = 0x0003)
        val pdu06 = ModbusPduCodec.encodeRequest(req06)
        assertEquals("06 00 01 00 03", HexFormatUtil.toHexString(pdu06))
        assertEquals(req06, ModbusPduCodec.decodeRequest(pdu06))
        val resp06 = assertIs<ModbusResponse.WriteSingleRegister>(ModbusPduCodec.decodeResponse(pdu06))
        assertEquals(0x0001, resp06.address)
        assertEquals(0x0003, resp06.value)

        // FC15 (0x0F, §6.11): Write Multiple Coils start=0x0013, 10 coils = 0xCD, 0x01
        val bits15 = listOf(true, false, true, true, false, false, true, true, true, false)
        val req15 = ModbusRequest.WriteMultipleCoils(startAddress = 0x0013, values = bits15)
        val pdu15 = ModbusPduCodec.encodeRequest(req15)
        assertEquals("0F 00 13 00 0A 02 CD 01", HexFormatUtil.toHexString(pdu15))
        assertEquals(req15, ModbusPduCodec.decodeRequest(pdu15))
        val resp15Pdu = HexFormatUtil.fromHexString("0F 00 13 00 0A")
        val resp15 = assertIs<ModbusResponse.WriteMultipleCoils>(ModbusPduCodec.decodeResponse(resp15Pdu))
        assertEquals(0x0013, resp15.startAddress)
        assertEquals(10, resp15.count)

        // FC16 (0x10, §6.12): Write Multiple Registers start=0x0001, values=[0x000A, 0x0102]
        val req16 = ModbusRequest.WriteMultipleRegisters(startAddress = 0x0001, values = listOf(0x000A, 0x0102))
        val pdu16 = ModbusPduCodec.encodeRequest(req16)
        assertEquals("10 00 01 00 02 04 00 0A 01 02", HexFormatUtil.toHexString(pdu16))
        assertEquals(req16, ModbusPduCodec.decodeRequest(pdu16))
        val resp16Pdu = HexFormatUtil.fromHexString("10 00 01 00 02")
        val resp16 = assertIs<ModbusResponse.WriteMultipleRegisters>(ModbusPduCodec.decodeResponse(resp16Pdu))
        assertEquals(0x0001, resp16.startAddress)
        assertEquals(2, resp16.count)
    }

    @Test
    fun exceptionResponsesHaveClearDescriptions() {
        for (code in listOf(1, 2, 3, 4, 5, 6, 8, 10, 11)) {
            val pdu = byteArrayOf(0x83.toByte(), code.toByte())
            val decoded = assertIs<ModbusResponse.Exception>(ModbusPduCodec.decodeResponse(pdu))
            assertEquals(0x03, decoded.originalFunctionCode)
            assertEquals(code, decoded.exceptionCode)
            assertNotNull(decoded.knownCode)
            assertTrue(decoded.messageId.contains("0x0${code.toString(16).uppercase()}") || decoded.messageId.contains("0x${code.toString(16).uppercase()}"))
            assertTrue(decoded.messageEn.isNotBlank())
        }
        val unknown = assertIs<ModbusResponse.Exception>(
            ModbusPduCodec.decodeResponse(byteArrayOf(0x84.toByte(), 0x7F)),
        )
        assertNull(unknown.knownCode)
        assertTrue(unknown.messageId.contains("0x7F"))
    }

    @Test
    fun mbapFramingAndValidation() {
        val pdu = HexFormatUtil.fromHexString("03 00 6B 00 03")
        val tcpFrame = MbapCodec.encode(transactionId = 0x1A2B, unitId = 1, pdu = pdu)
        assertEquals("1A 2B 00 00 00 06 01 03 00 6B 00 03", HexFormatUtil.toHexString(tcpFrame))

        val decoded = MbapCodec.decode(tcpFrame)
        assertEquals(0x1A2B, decoded.transactionId)
        assertEquals(0, decoded.protocolId)
        assertEquals(6, decoded.length)
        assertEquals(1, decoded.unitId)
        assertContentEquals(pdu, decoded.pdu)

        // Protocol ID bukan 0 harus ditolak
        val badProto = tcpFrame.clone().also { it[2] = 0x01 }
        assertFailsWith<ModbusProtocolException> { MbapCodec.decode(badProto) }

        // Field length tidak cocok harus ditolak
        val badLen = tcpFrame.clone().also { it[5] = 0x09 }
        assertFailsWith<ModbusProtocolException> { MbapCodec.decode(badLen) }
    }

    @Test
    fun modiconAndZeroBasedAddressMapping() {
        // 0-based desimal dan heksadesimal
        val zeroDec = ModbusAddress.resolve("0", AddressNotation.AUTO, FunctionCode.FC03_READ_HOLDING_REGISTERS)
        assertNotNull(zeroDec)
        assertEquals(0, zeroDec.protocolAddress)
        assertFalse(zeroDec.isModicon)
        assertEquals("FC03, alamat protokol 0", zeroDec.previewId(FunctionCode.FC03_READ_HOLDING_REGISTERS))
        assertEquals("FC03, protocol address 0", zeroDec.previewEn(FunctionCode.FC03_READ_HOLDING_REGISTERS))

        val zeroHex = ModbusAddress.resolve("0x0000", AddressNotation.AUTO, FunctionCode.FC03_READ_HOLDING_REGISTERS)
        assertNotNull(zeroHex)
        assertEquals(0, zeroHex.protocolAddress)
        assertEquals("FC03, alamat protokol 0", zeroHex.previewId(FunctionCode.FC03_READ_HOLDING_REGISTERS))

        val hex107 = ModbusAddress.resolve("0x006B", AddressNotation.AUTO, FunctionCode.FC03_READ_HOLDING_REGISTERS)
        assertNotNull(hex107)
        assertEquals(107, hex107.protocolAddress)

        // Notasi Modicon 5 digit: 00001, 10001, 30001, 40001
        val coil1 = ModbusAddress.resolve("00001", AddressNotation.AUTO)
        assertNotNull(coil1)
        assertEquals(0, coil1.protocolAddress)
        assertTrue(coil1.isModicon)
        assertEquals(FunctionCode.FC01_READ_COILS, coil1.suggestedFunctionCode)

        val di1 = ModbusAddress.resolve("10001", AddressNotation.AUTO)
        assertNotNull(di1)
        assertEquals(0, di1.protocolAddress)
        assertEquals(FunctionCode.FC02_READ_DISCRETE_INPUTS, di1.suggestedFunctionCode)

        val ir1 = ModbusAddress.resolve("30001", AddressNotation.AUTO)
        assertNotNull(ir1)
        assertEquals(0, ir1.protocolAddress)
        assertEquals(FunctionCode.FC04_READ_INPUT_REGISTERS, ir1.suggestedFunctionCode)

        val hr1 = ModbusAddress.resolve("40001", AddressNotation.AUTO)
        assertNotNull(hr1)
        assertEquals(0, hr1.protocolAddress)
        assertEquals(FunctionCode.FC03_READ_HOLDING_REGISTERS, hr1.suggestedFunctionCode)
        assertEquals("FC03, alamat protokol 0", hr1.previewId(FunctionCode.FC03_READ_HOLDING_REGISTERS))

        val hr108 = ModbusAddress.resolve("40108", AddressNotation.AUTO)
        assertNotNull(hr108)
        assertEquals(107, hr108.protocolAddress)

        // Notasi Modicon 6 digit: 400001 dan 465536
        val hr6Digit1 = ModbusAddress.resolve("400001", AddressNotation.AUTO)
        assertNotNull(hr6Digit1)
        assertEquals(0, hr6Digit1.protocolAddress)
        assertTrue(hr6Digit1.isModicon)
        assertEquals("FC03, alamat protokol 0", hr6Digit1.previewId(FunctionCode.FC03_READ_HOLDING_REGISTERS))

        val hr6DigitMax = ModbusAddress.resolve("465536", AddressNotation.AUTO)
        assertNotNull(hr6DigitMax)
        assertEquals(65535, hr6DigitMax.protocolAddress)
    }

    @Test
    fun float32FourByteOrdersAndLinearScale() {
        // 123.456f = 0x42F6E979 (A=42, B=F6, C=E9, D=79)
        for (order in ByteOrder.entries) {
            val regs = RegisterDecoder.encodeFloat32(123.456f, order)
            val pair = DecodedRegisterPair(0, regs[0], regs[1])
            val decoded = pair.forOrder(order)
            assertTrue(abs(decoded.float32 - 123.456f) < 1e-4f, "Order $order gagal untuk 123.456f")
        }
        // Pastikan urutan ABCD spesifik menghasilkan [0x42F6, 0xE979] dan CDAB [0xE979, 0x42F6]
        assertEquals(listOf(0x42F6, 0xE979), RegisterDecoder.encodeFloat32(123.456f, ByteOrder.ABCD))
        assertEquals(listOf(0xE979, 0x42F6), RegisterDecoder.encodeFloat32(123.456f, ByteOrder.CDAB))
        assertEquals(listOf(0xF642, 0x79E9), RegisterDecoder.encodeFloat32(123.456f, ByteOrder.BADC))
        assertEquals(listOf(0x79E9, 0xF642), RegisterDecoder.encodeFloat32(123.456f, ByteOrder.DCBA))

        // -1.0f = 0xBF800000 (A=BF, B=80, C=00, D=00)
        for (order in ByteOrder.entries) {
            val regs = RegisterDecoder.encodeFloat32(-1.0f, order)
            val pair = DecodedRegisterPair(10, regs[0], regs[1])
            assertEquals(-1.0f, pair.forOrder(order).float32)
        }
        assertEquals(listOf(0xBF80, 0x0000), RegisterDecoder.encodeFloat32(-1.0f, ByteOrder.ABCD))
        assertEquals(listOf(0x0000, 0xBF80), RegisterDecoder.encodeFloat32(-1.0f, ByteOrder.CDAB))
        assertEquals(listOf(0x80BF, 0x0000), RegisterDecoder.encodeFloat32(-1.0f, ByteOrder.BADC))
        assertEquals(listOf(0x0000, 0x80BF), RegisterDecoder.encodeFloat32(-1.0f, ByteOrder.DCBA))

        // Skala linear: raw 0..27648 -> 0..100 bar
        val scale = LinearScale(rawMin = 0.0, rawMax = 27648.0, engMin = 0.0, engMax = 100.0, unit = "bar")
        assertEquals(50.0, scale.scale(13824.0), 1e-6)
        assertEquals(13824.0, scale.unscale(50.0), 1e-6)
        val decReg = DecodedRegister(address = 8, rawWord = 13824, scale = scale)
        assertEquals("50 bar", decReg.scaledText)
    }

    @Test
    fun rtuTimingFollowsModbusSerialSpec() {
        // Pada 9600 bps, 11 bit/char -> t3.5 = ceil(3.5 * 11 * 1_000_000 / 9600) = 4011 us
        assertEquals(4011L, RtuTiming.interFrameDelayMicros(9600, 11))
        // Pada > 19200 bps (mis. 38400, 115200) -> tetap 1750 us sesuai spesifikasi V1.02 §2.5.1.1
        assertEquals(1750L, RtuTiming.interFrameDelayMicros(38400, 11))
        assertEquals(1750L, RtuTiming.interFrameDelayMicros(115200, 11))
    }

    @Test
    fun localModbusTcpServerReadAndWriteOverRealSocket() {
        val frames = mutableListOf<FrameLogEntry>()
        LocalModbusTcpServer().use { server ->
            TcpSocketTransport(
                host = "127.0.0.1",
                port = server.port,
                frameLogger = { frames.add(it) },
            ).use { transport ->
                // 1. Baca 2 holding register awal (berisi 123.456f dalam ABCD)
                val readResp = assertIs<ModbusResponse.ReadRegisters>(
                    transport.exchange(1, ModbusRequest.ReadHoldingRegisters(0, 2)),
                )
                assertEquals(listOf(0x42F6, 0xE979), readResp.registers)
                val pair = DecodedRegisterPair(0, readResp.registers[0], readResp.registers[1])
                assertEquals(123.456f, pair.forOrder(ByteOrder.ABCD).float32, 1e-4f)

                // 2. Tulis 1 register (FC06) ke alamat 20 lalu baca kembali
                val writeSingle = assertIs<ModbusResponse.WriteSingleRegister>(
                    transport.exchange(1, ModbusRequest.WriteSingleRegister(20, 0x1234)),
                )
                assertEquals(20, writeSingle.address)
                assertEquals(0x1234, writeSingle.value)

                // 3. Tulis banyak register (FC16) nilai Float32 -1.0f ke alamat 30..31 lalu baca kembali
                val negOneRegs = RegisterDecoder.encodeFloat32(-1.0f, ByteOrder.CDAB)
                val writeMulti = assertIs<ModbusResponse.WriteMultipleRegisters>(
                    transport.exchange(1, ModbusRequest.WriteMultipleRegisters(30, negOneRegs)),
                )
                assertEquals(2, writeMulti.count)

                val verifyMulti = assertIs<ModbusResponse.ReadRegisters>(
                    transport.exchange(1, ModbusRequest.ReadHoldingRegisters(30, 2)),
                )
                assertEquals(-1.0f, DecodedRegisterPair(30, verifyMulti.registers[0], verifyMulti.registers[1]).forOrder(ByteOrder.CDAB).float32)

                // 4. Tulis & baca coil (FC05 & FC01)
                transport.exchange(1, ModbusRequest.WriteSingleCoil(5, true))
                val coilResp = assertIs<ModbusResponse.ReadBits>(
                    transport.exchange(1, ModbusRequest.ReadCoils(5, 1)),
                )
                assertEquals(listOf(true), coilResp.bits)

                // 5. Alamat di luar batas menghasilkan Exception 0x02 (Illegal Data Address)
                val exResp = assertIs<ModbusResponse.Exception>(
                    transport.exchange(1, ModbusRequest.ReadHoldingRegisters(2000, 2)),
                )
                assertEquals(ModbusExceptionCode.ILLEGAL_DATA_ADDRESS.code, exResp.exceptionCode)
            }
        }
        assertTrue(frames.any { it.direction == FrameDirection.TX })
        assertTrue(frames.any { it.direction == FrameDirection.RX })
        assertTrue(frames.any { it.exceptionCode == ModbusExceptionCode.ILLEGAL_DATA_ADDRESS.code })
    }

    @Test
    fun fakeSerialRtuTransportHandlesReadWriteCrcErrorAndTimeout() {
        val frames = mutableListOf<FrameLogEntry>()
        val endpoint = DemoSerialEndpoint()
        val rtu = RtuTransport(
            endpoint = endpoint,
            baudRate = 19200,
            rtsToggleRs485 = true,
            frameLogger = { frames.add(it) },
            sleeper = { /* tanpa tunggu di unit test */ },
        )

        // 1. Baca normal Slave 1
        val resp = assertIs<ModbusResponse.ReadRegisters>(
            rtu.exchange(1, ModbusRequest.ReadHoldingRegisters(0, 2), timeoutMs = 200),
        )
        assertEquals(listOf(0x42F6, 0xE979), resp.registers)

        // 2. Simulasi CRC salah pada frame balasan
        endpoint.corruptNextCrc = true
        assertFailsWith<ModbusCrcException> {
            rtu.exchange(1, ModbusRequest.ReadHoldingRegisters(0, 2), timeoutMs = 200)
        }
        assertTrue(frames.any { it.crcValid == false })

        // 3. Simulasi timeout (Slave ID 99 tidak aktif / tidak merespons)
        var clockMs = 1000L
        val rtuTimeout = RtuTransport(
            endpoint = endpoint,
            baudRate = 9600,
            sleeper = {},
            clock = {
                clockMs += 60L
                clockMs
            },
        )
        assertFailsWith<ModbusTimeoutException> {
            rtuTimeout.exchange(99, ModbusRequest.ReadHoldingRegisters(0, 2), timeoutMs = 150)
        }
    }

    @Test
    fun proEntitlementAndProfileSerialization() {
        val now = 1_000_000L
        assertTrue(ProEntitlement.isTrialActive(now - 1000L, now))
        assertFalse(ProEntitlement.isTrialActive(now - ProEntitlement.TRIAL_DURATION_MS - 1L, now))
        assertEquals(ProLabelSource.LIFETIME, ProEntitlement.labelSource(true, 0L, 0L, now))

        val profiles = listOf(
            ConnectionProfile(
                name = "VFD Panel #1\tUtama",
                mode = ConnectionMode.RTU_USB,
                baudRate = 19200,
                parity = SerialParity.EVEN,
                unitId = 2,
                rtsRs485 = true,
            ),
        )
        val encoded = ConnectionProfile.encodeList(profiles)
        assertEquals(profiles, ConnectionProfile.decodeList(encoded))

        val csv = CsvExporter.formatCsv(
            listOf(DataSample(timestampMillis = 123456L, startAddress = 0, registers = listOf(0x42F6, 0xE979))),
        )
        assertTrue(csv.contains("123.456"))
    }
}
