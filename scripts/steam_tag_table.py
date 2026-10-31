"""Shared encoder and streaming reader for the offline Steam community tag table."""
import json
import struct


def entries(path):
    """Stream the top-level JSON object without retaining descriptions/images."""
    decoder = json.JSONDecoder()
    with path.open(encoding="utf-8") as stream:
        buffer = ""

        def trim():
            nonlocal buffer
            buffer = buffer.lstrip()
            while not buffer:
                buffer = stream.read(256 * 1024)
                if not buffer:
                    raise ValueError("Unexpected end of JSON")
                buffer = buffer.lstrip()

        def consume(char):
            nonlocal buffer
            trim()
            assert buffer[0] == char
            buffer = buffer[1:]

        def value():
            nonlocal buffer
            trim()
            while True:
                try:
                    result, end = decoder.raw_decode(buffer)
                    buffer = buffer[end:]
                    return result
                except json.JSONDecodeError:
                    more = stream.read(256 * 1024)
                    if not more:
                        raise
                    buffer += more

        consume("{")
        trim()
        while buffer[0] != "}":
            key = value()
            consume(":")
            yield int(key), value()
            trim()
            if buffer[0] != ",":
                break
            consume(",")
            trim()
        consume("}")
        assert not buffer.strip() and not stream.read().strip()


def varint(value):
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return result


def pack(rows, names):
    # Searchable blocks: offset + first absolute AppID, then delta AppIDs.
    # Every row retains its ranked list. No vote counts are stored.
    ids = {name: index for index, name in enumerate(names)}
    dictionary = bytearray()
    for name in names:
        encoded = name.encode("utf-8")
        dictionary += varint(len(encoded)) + encoded
    payload = bytearray()
    offsets = []
    previous = 0
    for index, (app_id, tags) in enumerate(rows):
        if index % 32 == 0:
            offsets.append(len(payload))
            previous = 0
        payload += varint(app_id - previous) + varint(len(tags))
        for tag in tags:
            payload += varint(ids[tag])
        previous = app_id
    return (struct.pack(">4siiiii", b"STTG", 1, len(names), len(rows), 32, len(payload))
            + dictionary + b"".join(struct.pack(">i", x) for x in offsets) + payload)


def verify(binary, rows, names):
    magic, version, count, row_count, block_size, length = struct.unpack_from(">4siiiii", binary)
    assert (magic, version, count, row_count, block_size) == (b"STTG", 1, len(names), len(rows), 32)
    position = 24

    def integer():
        nonlocal position
        value = shift = 0
        while True:
            byte = binary[position]
            position += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value
            shift += 7

    for name in names:
        size = integer()
        assert binary[position:position + size].decode() == name
        position += size
    blocks = (len(rows) + 31) // 32
    offsets = struct.unpack_from(f">{blocks}i", binary, position)
    position += blocks * 4
    start = position
    previous = 0
    for index, (app_id, tags) in enumerate(rows):
        if index % 32 == 0:
            assert position - start == offsets[index // 32]
            previous = 0
        actual_id = previous + integer()
        actual_tags = [names[integer()] for _ in range(integer())]
        assert (actual_id, actual_tags) == (app_id, tags)
        previous = actual_id
    assert position == len(binary) and position - start == length


