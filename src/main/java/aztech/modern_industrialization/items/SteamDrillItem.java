/*
 * MIT License
 *
 * Copyright (c) 2020 Azercoco & Technici4n
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package aztech.modern_industrialization.items;

import aztech.modern_industrialization.mixin_impl.SteamDrillHooks;
import aztech.modern_industrialization.util.Simulation;
import draylar.magna.api.MagnaTool;
import java.util.List;
import net.fabricmc.fabric.api.registry.FuelRegistry;
import net.fabricmc.fabric.api.tool.attribute.v1.DynamicAttributeTool;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tag.Tag;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * The steam drill. The item stack contains the following information:
 * burnTicks: integer, the remaining burn ticks of the fuel (as many as if the
 * fuel was used in a furnace). water: integer, the remaining ticks of water
 * (when full: 18000 ticks i.e. 15 minutes).
 */
public class SteamDrillItem extends Item implements DynamicAttributeTool, MagnaTool {
    private static final int FULL_WATER = 18000;

    public SteamDrillItem(Settings settings) {
        super(settings);
    }

    @Override
    public int getMiningLevel(Tag<Item> tag, BlockState state, ItemStack stack, @Nullable LivingEntity user) {
        if (isIn(tag) && canMine(stack, user)) {
            return 2;
        }
        return 0;
    }

    @Override
    public float getMiningSpeedMultiplier(Tag<Item> tag, BlockState state, ItemStack stack, @Nullable LivingEntity user) {
        if (isIn(tag) && canMine(stack, user)) {
            return 4.0f;
        }
        return 1.0f;
    }

    @Override
    public int getRadius(ItemStack stack) {
        return 1;
    }

    @Override
    public boolean playBreakEffects() {
        return true;
    }

    @Override
    public boolean postMine(ItemStack stack, World world, BlockState state, BlockPos pos, LivingEntity miner) {
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.getInt("water") > 0) {
            if (tag.getInt("burnTicks") == 0) {
                int burnTicks = consumeFuel(stack, miner, Simulation.ACT);
                tag.putInt("burnTicks", burnTicks);
            }
        }
        return true;
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack itemStack = user.getStackInHand(hand);
        BlockHitResult hitResult = raycast(world, user, RaycastContext.FluidHandling.ANY);
        if (hitResult.getType() != HitResult.Type.BLOCK)
            return TypedActionResult.pass(itemStack);
        FluidState fluidState = world.getFluidState(hitResult.getBlockPos());
        if (fluidState.getFluid() == Fluids.WATER || fluidState.getFluid() == Fluids.FLOWING_WATER) {
            itemStack.getOrCreateTag().putInt("water", FULL_WATER);
            return TypedActionResult.success(itemStack, world.isClient());
        }
        return super.use(world, user, hand);
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            tooltip.add(new TranslatableText("text.modern_industrialization.water_percent", tag.getInt("water") * 100 / FULL_WATER));
            int burnTicks = tag.getInt("burnTicks");
            if (burnTicks > 0) {
                tooltip.add(new TranslatableText("text.modern_industrialization.seconds_left", burnTicks / 20));
            }
        }
    }

    @Override
    public void inventoryTick(ItemStack stack, World world, Entity entity, int slot, boolean selected) {
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            int burnTicks = tag.getInt("burnTicks");
            if (burnTicks > 0) {
                tag.putInt("burnTicks", burnTicks - 1);
                tag.putInt("water", Math.max(0, tag.getInt("water") - 1));
            }
        }
    }

    public static boolean canMine(ItemStack stack, @Nullable LivingEntity user) {
        CompoundTag tag = stack.getTag();
        if (tag == null || tag.getInt("water") == 0) {
            return false;
        }
        return tag.getInt("burnTicks") > 0 || consumeFuel(stack, user, Simulation.SIMULATE) > 0;
    }

    private static int consumeFuel(ItemStack stack, @Nullable LivingEntity user, Simulation simulation) {
        PlayerEntity player = findUser(user);
        if (player != null) {
            PlayerInventory inv = player.inventory;
            int drillSlot = -1;
            for (int i = 0; i < 9; ++i) {
                if (inv.getStack(i) == stack) {
                    drillSlot = i;
                }
            }
            if (drillSlot == -1)
                return 0;
            for (int offset = -1; offset <= 1; offset += 2) {
                int adjSlot = drillSlot + offset;
                if (adjSlot < 0 || adjSlot >= 9)
                    continue;
                ItemStack adjStack = inv.getStack(adjSlot);
                Integer burnTicks = FuelRegistry.INSTANCE.get(adjStack.getItem());
                if (burnTicks != null && burnTicks > 0) {
                    if (simulation.isActing()) {
                        Item adjItem = adjStack.getItem();
                        adjStack.decrement(1);
                        if (adjItem.hasRecipeRemainder()) {
                            inv.setStack(adjSlot, new ItemStack(adjItem.getRecipeRemainder()));
                        }
                    }
                    return burnTicks;
                }
            }
        }
        return 0;
    }

    /**
     * Try to find a suitable user.
     */
    @Nullable
    private static PlayerEntity findUser(@Nullable LivingEntity entity) {
        if (entity instanceof PlayerEntity) {
            return (PlayerEntity) entity;
        }
        if (Thread.currentThread().getName().equals("Render thread")) {
            // This is necessary for Magna's overlay
            return MinecraftClient.getInstance().player;
        }
        return SteamDrillHooks.getCurrentPlayer();
    }
}