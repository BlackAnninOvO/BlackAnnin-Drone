package com.blackannin.drone.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 轻量 A* 体素寻路：以 1 格为粒度搜索无人机可通过的最短路径。
 *
 * <p>为什么需要它：纯贪心转向在"封闭房间 + 小洞口"这类场景下无法定位入口
 * （洞口可能不在目标方向上），必须在房间外壁整体搜索才能找到路。</p>
 *
 * <p>通行判定按无人机的实际碰撞箱（0.6×0.4×0.6）逐格检测，
 * 因此 1 格宽的活板门洞口、竖井、门框都能通过；水、打开的活板门无碰撞判定，直接放行。</p>
 */
final class DronePathfinder {
    /** 搜索范围：以无人机为中心的水平/垂直半径（格） */
    private static final int RANGE_XZ = 20;
    private static final int RANGE_Y = 10;
    /** 单次搜索最大扩展节点数，防止极端情况卡顿 */
    private static final int MAX_EXPANSIONS = 3000;
    /** 斜向移动代价 */
    private static final double DIAGONAL_COST = 1.4142D;

    /** 无人机碰撞箱（略窄于 0.6，避免贴墙误判） */
    private static final double HALF_WIDTH = 0.28D;
    private static final double HEIGHT = 0.4D;

    private static final int[][] OFFSETS = buildOffsets();

    private DronePathfinder() {
    }

    /**
     * 从无人机当前位置搜索到目标点的路径。
     *
     * @return 世界坐标路点（格中心），不含起点；无路径时返回空列表
     */
    static List<Vec3> findPath(Level level, Entity drone, Vec3 goal) {
        BlockPos startPos = BlockPos.containing(drone.position());
        BlockPos goalPos = BlockPos.containing(goal);

        if (Math.abs(startPos.getX() - goalPos.getX()) > RANGE_XZ
                || Math.abs(startPos.getY() - goalPos.getY()) > RANGE_Y
                || Math.abs(startPos.getZ() - goalPos.getZ()) > RANGE_XZ) {
            return List.of();
        }

        Map<Long, Double> gScore = new HashMap<>();
        Map<Long, Long> cameFrom = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));

        long startKey = startPos.asLong();
        gScore.put(startKey, 0.0D);
        open.add(new Node(startKey, startPos, 0.0D, heuristic(startPos, goalPos)));

        int expansions = 0;
        while (!open.isEmpty() && expansions++ < MAX_EXPANSIONS) {
            Node current = open.poll();
            if (current.pos.equals(goalPos)) {
                return reconstruct(cameFrom, current.pos);
            }
            double currentG = gScore.getOrDefault(current.key, Double.MAX_VALUE);
            if (current.g > currentG + 1.0E-6D) {
                continue;
            }
            for (int[] offset : OFFSETS) {
                BlockPos next = current.pos.offset(offset[0], offset[1], offset[2]);
                if (Math.abs(next.getX() - startPos.getX()) > RANGE_XZ
                        || Math.abs(next.getZ() - startPos.getZ()) > RANGE_XZ
                        || Math.abs(next.getY() - startPos.getY()) > RANGE_Y) {
                    continue;
                }
                if (!isPassable(level, drone, next) || !isStepClear(level, drone, current.pos, offset)) {
                    continue;
                }
                double stepCost = (offset[0] != 0 && offset[2] != 0) ? DIAGONAL_COST : 1.0D;
                double tentative = currentG + stepCost;
                long nextKey = next.asLong();
                if (tentative < gScore.getOrDefault(nextKey, Double.MAX_VALUE) - 1.0E-6D) {
                    gScore.put(nextKey, tentative);
                    cameFrom.put(nextKey, current.key);
                    open.add(new Node(nextKey, next, tentative, tentative + heuristic(next, goalPos)));
                }
            }
        }
        return List.of();
    }

    /** 由终点回溯出路径（世界坐标，格中心） */
    private static List<Vec3> reconstruct(Map<Long, Long> cameFrom, BlockPos goal) {
        List<Vec3> path = new ArrayList<>();
        BlockPos cursor = goal;
        while (cursor != null) {
            path.add(Vec3.atCenterOf(cursor));
            Long parent = cameFrom.get(cursor.asLong());
            cursor = parent == null ? null : BlockPos.of(parent);
        }
        // 去掉起点（最后加入的），并反转为从近到远
        if (!path.isEmpty()) {
            path.remove(path.size() - 1);
        }
        java.util.Collections.reverse(path);
        return path;
    }

    /** 该格能否容纳无人机 */
    private static boolean isPassable(Level level, Entity drone, BlockPos pos) {
        AABB box = new AABB(
                pos.getX() + 0.5D - HALF_WIDTH, pos.getY(), pos.getZ() + 0.5D - HALF_WIDTH,
                pos.getX() + 0.5D + HALF_WIDTH, pos.getY() + HEIGHT, pos.getZ() + 0.5D + HALF_WIDTH);
        return level.noCollision(drone, box);
    }

    /** 斜向/竖向跨越时不允许穿角：各轴向投影格都需可通行 */
    private static boolean isStepClear(Level level, Entity drone, BlockPos from, int[] offset) {
        if (offset[0] != 0 && !isPassable(level, drone, from.offset(offset[0], 0, 0))) {
            return false;
        }
        if (offset[2] != 0 && !isPassable(level, drone, from.offset(0, 0, offset[2]))) {
            return false;
        }
        return offset[1] == 0 || isPassable(level, drone, from.offset(0, offset[1], 0));
    }

    private static double heuristic(BlockPos from, BlockPos to) {
        double dx = from.getX() - to.getX();
        double dy = from.getY() - to.getY();
        double dz = from.getZ() - to.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 26 邻域（按距离由近到远，便于优先展开直线方向） */
    private static int[][] buildOffsets() {
        List<int[]> list = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) {
                        list.add(new int[]{dx, dy, dz});
                    }
                }
            }
        }
        list.sort(Comparator.comparingDouble(o -> Math.abs(o[0]) + Math.abs(o[1]) + Math.abs(o[2])));
        return list.toArray(new int[0][]);
    }

    /** A* 节点 */
    private static final class Node {
        final long key;
        final BlockPos pos;
        final double g;
        final double f;

        Node(long key, BlockPos pos, double g, double f) {
            this.key = key;
            this.pos = pos;
            this.g = g;
            this.f = f;
        }
    }
}
