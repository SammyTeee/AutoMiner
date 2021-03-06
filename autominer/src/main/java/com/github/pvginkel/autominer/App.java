package com.github.pvginkel.autominer;

import com.github.pvginkel.autominer.nbt.*;
import com.github.pvginkel.autominer.support.OutputWriter;
import com.github.pvginkel.autominer.support.Point;
import com.github.pvginkel.autominer.support.Rectangle;
import com.github.pvginkel.autominer.support.Vector;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.*;
import java.util.stream.Collectors;

public class App {
    // Depths where diamond is located.

    private static final int MINE_MIN_Y = 0;
    private static final int MINE_MAX_Y = 15;

    // Depths where we're going to mine. We add a bit to Y because we may be mining
    // up a bit when we find something.

    private static final int MIN_Y = MINE_MIN_Y;
    private static final int MAX_Y = MINE_MAX_Y + 10;

    // Comfortable level to start mining.

    private static final int MINING_Y = 11;

    private static final String CACHE_FILE = "cache.dat";

    static final Random RANDOM = new Random();

    public static void main(String[] args) throws Exception {
        File cacheFile = new File(CACHE_FILE);
        if (!cacheFile.exists()) {
            System.out.println("Building cache file");
            buildCache(args[0]);
        }

        System.out.println("Loading cache file...");

        BlockMap blockMap = BlockMap.load(cacheFile);

        final int iterations = 10;
        final int mineLength = 1000;

        List<Vector> starts = new ArrayList<>();
        Map<Block, Table> tables = new HashMap<>();

        for (int y = 9; y <= 13; y++) {
            System.out.println();
            System.out.println("Depth: " + y);
            System.out.println();

            for (int i = 0; i < iterations; i++) {
                starts.add(new Vector(
                    blockMap.getOffset().getX() + 50 + RANDOM.nextInt(blockMap.getCx() - 100),
                    y,
                    blockMap.getOffset().getZ() + 50 + RANDOM.nextInt(blockMap.getCz() - 100 - mineLength)
                ));
            }

            MinedCounter mined = runStrategy(blockMap, mineLength, starts, 0, 1);

//            System.out.println(mined);

            System.out.println(String.format(
                "Single branch: %s: %.1f, %s: %.1f",
                Blocks.DIAMOND_ORE.getName(),
                mined.get(Blocks.DIAMOND_ORE),
                Blocks.LAVA.getName(),
                mined.get(Blocks.LAVA)
            ));

            for (MinedCounter.Entry entry : mined.entrySet()) {
                getTable(tables, entry).set(Integer.toString(y), "Single branch", entry.getValue());
            }

            final int branches = 4;

            for (int branchDistance = 2; branchDistance <= 7; branchDistance++) {
//                System.out.println("Running strategy x+" + branchDistance + "+" + branchDistance + " at " + y);

                mined = runStrategy(blockMap, mineLength, starts, branchDistance, branches);
                mined.average(branches);

//            System.out.println(mined);

                System.out.println(String.format(
                    "Strategy x+%d: %s: %.1f, %s: %.1f",
                    branchDistance,
                    Blocks.DIAMOND_ORE.getName(),
                    mined.get(Blocks.DIAMOND_ORE),
                    Blocks.LAVA.getName(),
                    mined.get(Blocks.LAVA)
                ));

                for (MinedCounter.Entry entry : mined.entrySet()) {
                    getTable(tables, entry).set(Integer.toString(y), String.format("Strategy x+%d", branchDistance), entry.getValue());
                }
            }
        }

        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream("out.txt"))) {
            OutputWriter ow = new OutputWriter(writer);

            for (Map.Entry<Block, Table> entry : tables.entrySet()) {
                ow.write(entry.getKey().getName());
                ow.nl();
                entry.getValue().write(ow);
                ow.nl();
            }
        }
    }

    private static Table getTable(Map<Block, Table> tables, MinedCounter.Entry entry) {
        Table table = tables.get(entry.getBlock());
        if (table == null) {
            table = new Table();
            tables.put(entry.getBlock(), table);
        }
        return table;
    }

    private static MinedCounter runStrategy(BlockMap blockMap, int mineLength, List<Vector> starts, int branchOffset, int branches) {
        MinedCounter mined = new MinedCounter();

        for (Vector start : starts) {
            Miner miner = new Miner(blockMap.clone());

            for (int x = 0; x < branches; x++) {
                Vector currentStart = new Vector(
                    start.getX() + x * branchOffset,
                    start.getY(),
                    start.getZ()
                );

                miner.run(currentStart, new Point(0, 1), mineLength);
            }

//            System.out.println(String.format("Iteration %d", i + 1));
//            System.out.println(miner.getMined());

            mined.addAll(miner.getMined());
        }

        mined.average(starts.size());

        return mined;
    }

    private static void buildCache(String arg) throws Exception {
        System.out.println("Loading map...");

        List<Chunk> chunks = loadChunks(arg);

        Rectangle boundingBox = findBoundingBox(chunks);

        // Convert the chunk bounding box to coordinates bounding box.

        boundingBox = new Rectangle(
            boundingBox.getX1() * 16,
            boundingBox.getY1() * 16,
            boundingBox.getX2() * 16,
            boundingBox.getY2() * 16
        );

        System.out.println("Active bounding box: " + boundingBox.getX1() + "x" + boundingBox.getY1() + " " + boundingBox.getX2() + "x" + boundingBox.getY2());

        // Build a map of all blocks located at the target coordinates. We add
        // room to Y.

        System.out.println("Building block cache...");

        BlockMap blockMap = BlockMap.build(chunks, boundingBox, MIN_Y, MAX_Y);

        blockMap.save(new File(CACHE_FILE));
    }

    private static List<Chunk> loadChunks(String arg) throws Exception {
        File region = new File(arg, "region");
        final List<Chunk> chunks = new ArrayList<>();

        for (File file : region.listFiles()) {
            if (file.getName().endsWith(".mca")) {
                final Point offset = parseMcaFileName(file.getName());

                try (RegionFile rf = new RegionFile(file)) {
                    for (int z = 0; z < 32; z++) {
                        for (int x = 0; x < 32; x++) {
                            if (rf.hasChunk(x, z)) {
                                try (NBTStreamReader sr = rf.getChunk(x, z)) {
                                    final int xx = x;
                                    final int zz = z;

                                    new NBTWalker(sr) {
                                        boolean inSections;
                                        byte y;
                                        byte[] blocks;

                                        @Override
                                        public boolean value(String name, byte value) throws Exception {
                                            if ("Y".equals(name)) {
                                                y = value;
                                            }
                                            return super.value(name, value);
                                        }

                                        @Override
                                        public boolean value(String name, byte[] value) throws Exception {
                                            if ("Blocks".equals(name)) {
                                                blocks = value;
                                            }
                                            return true;
                                        }

                                        @Override
                                        public boolean list(String name, int length) throws Exception {
                                            inSections = "Sections".equals(name);
                                            super.list(name, length);
                                            return !inSections;
                                        }

                                        @Override
                                        public boolean compound(String name) throws Exception {
                                            if (!super.compound(name)) {
                                                return false;
                                            }

                                            if (inSections) {
                                                chunks.add(new Chunk(
                                                    new Vector(
                                                        offset.getX() * 32 + xx,
                                                        y,
                                                        offset.getY() * 32 + zz
                                                    ),
                                                    blocks
                                                ));
                                            }
                                            return true;
                                        }
                                    };
                                }
                            }
                        }
                    }
                }
            }
        }

        return chunks;
    }

    private static Point parseMcaFileName(String name) {
        name = name.substring(2, name.length() - 4);
        int index = name.indexOf('.');
        int x = Integer.parseInt(name.substring(0, index));
        int z = Integer.parseInt(name.substring(index + 1));
        return new Point(x, z);
    }

    private static Rectangle findBoundingBox(List<Chunk> chunks) {
        Set<Point> points = chunks.stream()
            .map(p -> new Point(p.getPosition().getX(), p.getPosition().getZ()))
            .collect(Collectors.toSet());

        // Find the bounding box of the chunks.

        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (Point point : points) {
            minX = Math.min(minX, point.getX());
            minZ = Math.min(minZ, point.getY());
            maxX = Math.max(maxX, point.getX());
            maxZ = Math.max(maxZ, point.getY());
        }

        // See whether we're missing any Z's on any of the X chunks.

        int validMinX = minX;
        int validMaxX = maxX;

        // Remove a little bit from the top and bottom to find a larger bounding block.

        minZ += 5;
        maxZ -= 5;

        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                if (!points.contains(new Point(x, z))) {
                    if (x > (minX + maxX) / 2) {
                        validMaxX = Math.min(validMaxX, x - 1);
                    } else {
                        validMinX = Math.max(validMinX, x + 1);
                    }
                }
            }
        }

        minX = validMinX;
        maxX = validMaxX;

        return new Rectangle(minX, minZ, maxX, maxZ);
    }
}
