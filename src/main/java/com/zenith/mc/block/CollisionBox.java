package com.zenith.mc.block;

public record CollisionBox(float minX, float maxX, float minY, float maxY, float minZ, float maxZ) {
    public boolean intersects(final CollisionBox collisionBox) {
        return this.maxX >= collisionBox.minX && this.minX <= collisionBox.maxX
            && this.maxZ >= collisionBox.minZ && this.minZ <= collisionBox.maxZ
            && this.maxY >= collisionBox.minY && this.minY <= collisionBox.maxY;
    }
}


