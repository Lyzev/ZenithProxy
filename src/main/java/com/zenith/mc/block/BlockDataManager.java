package com.zenith.mc.block;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.FloatNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenith.util.Maps;
import com.zenith.util.math.MathHelper;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import lombok.SneakyThrows;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.DataPalette;
import org.jspecify.annotations.Nullable;

import java.util.*;

import static com.zenith.Shared.OBJECT_MAPPER;

public class BlockDataManager {
    private final Int2ObjectOpenHashMap<Block> blockStateIdToBlock;
    private final Int2ObjectOpenHashMap<List<CollisionBox>> blockStateIdToCollisionBoxes;
    private final Int2ObjectOpenHashMap<List<CollisionBox>> blockStateIdToInteractionBoxes;
    private final Int2ObjectOpenHashMap<FluidState> blockStateIdToFluidState = new Int2ObjectOpenHashMap<>(100, Maps.FAST_LOAD_FACTOR);

    public BlockDataManager() {
        int blockStateIdCount = BlockRegistry.REGISTRY.getIdMap().int2ObjectEntrySet().stream()
            .map(Map.Entry::getValue)
            .map(Block::maxStateId)
            .max(Integer::compareTo)
            .orElseThrow();
        blockStateIdToBlock = new Int2ObjectOpenHashMap<>(blockStateIdCount, Maps.FAST_LOAD_FACTOR);
        blockStateIdToCollisionBoxes = new Int2ObjectOpenHashMap<>(blockStateIdCount, Maps.FAST_LOAD_FACTOR);
        blockStateIdToInteractionBoxes = new Int2ObjectOpenHashMap<>(blockStateIdCount, Maps.FAST_LOAD_FACTOR);
        try {
            init();
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SneakyThrows
    private void init() {
        for (Int2ObjectMap.Entry<Block> entry : BlockRegistry.REGISTRY.getIdMap().int2ObjectEntrySet()) {
            var block = entry.getValue();
            for (int i = block.minStateId(); i <= block.maxStateId(); i++) {
                blockStateIdToBlock.put(i, block);
            }
        }
        initShapeCache("blockCollisionShapes", blockStateIdToCollisionBoxes);
        initShapeCache("blockInteractionShapes", blockStateIdToInteractionBoxes);
        try (JsonParser parser = OBJECT_MAPPER.getFactory().createParser(getClass().getResourceAsStream("/mcdata/fluidStates.json"))) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() == JsonToken.FIELD_NAME) {
                    String stateIdString = parser.getCurrentName();
                    parser.nextToken(); // move to object start
                    ObjectNode fluidStateNode = OBJECT_MAPPER.readValue(parser, ObjectNode.class);
                    // process fluidStateNode here
                    int stateId = Integer.parseInt(stateIdString);
                    boolean water = fluidStateNode.get("water").asBoolean();
                    boolean source = fluidStateNode.get("source").asBoolean();
                    int amount = fluidStateNode.get("amount").asInt();
                    boolean falling = fluidStateNode.get("falling").asBoolean();
                    blockStateIdToFluidState.put(stateId, new FluidState(water, source, amount, falling));
                }
            }
        }
        DataPalette.GLOBAL_PALETTE_BITS_PER_ENTRY = MathHelper.log2Ceil(blockStateIdToBlock.size());
    }

    @SneakyThrows
    private void initShapeCache(String name, Int2ObjectOpenHashMap<List<CollisionBox>> output) {
        try (JsonParser shapesParser = OBJECT_MAPPER.getFactory().createParser(getClass().getResourceAsStream(
                "/mcdata/" + name + ".json"))) {
            final Int2ObjectOpenHashMap<List<CollisionBox>> shapeIdToCollisionBoxes = new Int2ObjectOpenHashMap<>(100);

            // Move into the root object
            shapesParser.nextToken(); // START_OBJECT
            while (shapesParser.nextToken() != null) {
                if (shapesParser.currentToken() == JsonToken.FIELD_NAME) {
                    String fieldName = shapesParser.getCurrentName();
                    shapesParser.nextToken(); // move to field value
                    if ("shapes".equals(fieldName)) {
                        // Parse shapes object
                        while (shapesParser.nextToken() != JsonToken.END_OBJECT) {
                            if (shapesParser.currentToken() == JsonToken.FIELD_NAME) {
                                int shapeId = Integer.parseInt(shapesParser.getCurrentName());
                                shapesParser.nextToken(); // move to start array
                                List<CollisionBox> collisionBoxes = new ArrayList<>(2);
                                while (shapesParser.nextToken() != JsonToken.END_ARRAY) {
                                    float[] cbArr = new float[6];
                                    int idx = 0;
                                    // nested array of 6 floats
                                    while (shapesParser.nextToken() != JsonToken.END_ARRAY) {
                                        cbArr[idx++] = shapesParser.getFloatValue();
                                    }
                                    collisionBoxes.add(new CollisionBox(
                                            cbArr[0], cbArr[3], cbArr[1], cbArr[4], cbArr[2], cbArr[5]
                                    ));
                                }
                                shapeIdToCollisionBoxes.put(shapeId, collisionBoxes);
                            }
                        }
                    } else if ("blocks".equals(fieldName)) {
                        // Parse blocks object
                        while (shapesParser.nextToken() != JsonToken.END_OBJECT) {
                            if (shapesParser.currentToken() == JsonToken.FIELD_NAME) {
                                int blockId = Integer.parseInt(shapesParser.getCurrentName());
                                shapesParser.nextToken();
                                IntArrayList shapeIds = new IntArrayList(2);
                                if (shapesParser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
                                    shapeIds.add(shapesParser.getIntValue());
                                } else if (shapesParser.currentToken() == JsonToken.START_ARRAY) {
                                    while (shapesParser.nextToken() != JsonToken.END_ARRAY) {
                                        shapeIds.add(shapesParser.getIntValue());
                                    }
                                } else {
                                    throw new RuntimeException(
                                            "Unexpected shape node type: " + shapesParser.currentToken()
                            );
                                }
                                Block blockData = BlockRegistry.REGISTRY.get(blockId);
                                for (int i = blockData.minStateId(); i <= blockData.maxStateId(); i++) {
                                    int nextShapeId = shapeIds.size() == 1
                                            ? shapeIds.getInt(0)
                                            : shapeIds.getInt(i - blockData.minStateId());
                                    List<CollisionBox> collisionBoxes = shapeIdToCollisionBoxes.get(nextShapeId);
                                    output.put(i, collisionBoxes);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public @Nullable Block getBlockDataFromBlockStateId(int blockStateId) {
        Block blockData = blockStateIdToBlock.get(blockStateId);
        if (blockData == blockStateIdToBlock.defaultReturnValue()) return null;
        return blockData;
    }

    public List<CollisionBox> getCollisionBoxesFromBlockStateId(int blockStateId) {
        List<CollisionBox> collisionBoxes = blockStateIdToCollisionBoxes.get(blockStateId);
        if (collisionBoxes == blockStateIdToCollisionBoxes.defaultReturnValue()) return Collections.emptyList();
        return collisionBoxes;
    }

    public List<CollisionBox> getInteractionBoxesFromBlockStateId(int blockStateId) {
        List<CollisionBox> collisionBoxes = blockStateIdToInteractionBoxes.get(blockStateId);
        if (collisionBoxes == blockStateIdToInteractionBoxes.defaultReturnValue()) return Collections.emptyList();
        return collisionBoxes;
    }

    public List<LocalizedCollisionBox> localizeCollisionBoxes(List<CollisionBox> collisionBoxes, Block block, int x, int y, int z) {
        var offsetVec = block.offsetType().getOffsetFunction().offset(block, x, y, z);
        final List<LocalizedCollisionBox> localizedCollisionBoxes = new ArrayList<>(collisionBoxes.size());
        for (int i = 0; i < collisionBoxes.size(); i++) {
            var collisionBox = collisionBoxes.get(i);
            localizedCollisionBoxes.add(new LocalizedCollisionBox(
                collisionBox.minX() + offsetVec.getX() + x,
                collisionBox.maxX() + offsetVec.getX() + x,
                collisionBox.minY() + offsetVec.getY() + y,
                collisionBox.maxY() + offsetVec.getY() + y,
                collisionBox.minZ() + offsetVec.getZ() + z,
                collisionBox.maxZ() + offsetVec.getZ() + z,
                x, y, z
            ));
        }
        return localizedCollisionBoxes;
    }

    @Nullable
    public FluidState getFluidState(int blockStateId) {
        return blockStateIdToFluidState.get(blockStateId);
    }

    public boolean isAir(Block block) {
        return block == BlockRegistry.AIR || block == BlockRegistry.CAVE_AIR || block == BlockRegistry.VOID_AIR;
    }

    public float getBlockSlipperiness(Block block) {
        float slippy = 0.6f;
        if (block == BlockRegistry.ICE) slippy = 0.98f;
        if (block == BlockRegistry.SLIME_BLOCK) slippy = 0.8f;
        if (block == BlockRegistry.PACKED_ICE) slippy = 0.98f;
        if (block == BlockRegistry.FROSTED_ICE) slippy = 0.98f;
        if (block == BlockRegistry.BLUE_ICE) slippy = 0.989f;
        return slippy;
    }
}
