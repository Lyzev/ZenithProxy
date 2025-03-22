package com.zenith.discord;

import com.github.rfresh2.SimpleEventBus;
import com.zenith.Proxy;
import com.zenith.command.brigadier.CommandContext;
import com.zenith.command.brigadier.DiscordCommandContext;
import com.zenith.command.util.CommandOutputHelper;
import com.zenith.event.proxy.DiscordMessageSentEvent;
import com.zenith.feature.queue.Queue;
import com.zenith.module.impl.AutoReconnect;
import com.zenith.util.MentionUtil;
import lombok.Getter;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.ISnowflake;
import net.dv8tion.jda.api.entities.Icon;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.StatusChangeEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.*;
import net.dv8tion.jda.api.hooks.SimpleEventBusListener;
import net.dv8tion.jda.api.interactions.components.ActionComponent;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.Color;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.ShutdownException;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.internal.JDAImpl;
import net.dv8tion.jda.internal.utils.ShutdownReason;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Shared.*;
import static java.util.Arrays.asList;

public class DiscordBot {

    private TextChannel mainChannel;
    private TextChannel relayChannel;
    @Getter
    private boolean isRunning;
    private ScheduledFuture<?> presenceUpdateFuture;
    private ScheduledFuture<?> healthCheckFuture;
    protected JDA jda;
    public Optional<Instant> lastRelayMessage = Optional.empty();
    private final SimpleEventBus jdaEventBus = new SimpleEventBus();

    public DiscordBot() {
        this.isRunning = false;
        jdaEventBus.subscribe(
            this,
            of(MessageReceivedEvent.class, this::onMessageReceived),
            of(SessionRecreateEvent.class, e -> DISCORD_LOG.info("Session recreated")),
            of(SessionResumeEvent.class, e -> DISCORD_LOG.info("Session resumed")),
            of(ReadyEvent.class, e -> DISCORD_LOG.info("JDA ready")),
            of(SessionDisconnectEvent.class, e -> DISCORD_LOG.info("Session disconnected")),
            of(SessionInvalidateEvent.class, e -> DISCORD_LOG.info("Session invalidated")),
            of(StatusChangeEvent.class, e -> DISCORD_LOG.debug("JDA Status: {}", e.getNewStatus()))
        );
    }

    public synchronized void start() {
        createClient();

        if (CONFIG.discord.isUpdating) {
            handleProxyUpdateComplete();
        }
        this.presenceUpdateFuture = EXECUTOR.scheduleWithFixedDelay(
            this::updatePresence, 0L,
            15L, // discord rate limit
            TimeUnit.SECONDS);
        this.healthCheckFuture = EXECUTOR.scheduleWithFixedDelay(
            this::healthCheck, 5L,
            5L,
            TimeUnit.MINUTES);
        this.isRunning = true;
    }

    public JDA.Status getJdaStatus() {
        var jda = this.jda;
        if (jda == null) return JDA.Status.SHUTDOWN;
        return jda.getStatus();
    }

    public void healthCheck() {
        if (!isRunning) return;
        var jda = this.jda;
        if (jda == null) return;
        if (!(jda instanceof JDAImpl jdaImpl)) return;
        var wsConnected = jdaImpl.getClient().isConnected();
        if (wsConnected) return;
        DISCORD_LOG.info("Attempting discord gateway reconnect from status: {}", jda.getStatus());
        try {
            jdaImpl.getClient().reconnect(false);
        } catch (Exception e) {
            DISCORD_LOG.error("Failed reconnecting to discord", e);
        }
    }

    public void onMessageReceived(MessageReceivedEvent event) {
        var member = event.getMember();
        if (member == null) return;
        if (member.getUser().isBot() && CONFIG.discord.ignoreOtherBots) return;
        if (member.getId().equals(jda.getSelfUser().getId())) return;
        if (CONFIG.discord.chatRelay.enable
            && !CONFIG.discord.chatRelay.channelId.isEmpty()
            && event.getMessage().getChannelId().equals(CONFIG.discord.chatRelay.channelId)
            && !member.getId().equals(jda.getSelfUser().getId())) {
            EVENT_BUS.postAsync(new DiscordMessageSentEvent(sanitizeRelayInputMessage(event.getMessage().getContentRaw()), event));
            return;
        }
        if (!event.getMessage().getChannelId().equals(CONFIG.discord.channelId)) return;
        final String message = event.getMessage().getContentRaw();
        if (!message.startsWith(CONFIG.discord.prefix)) return;
        EXECUTOR.execute(() -> executeDiscordCommand(event, message, member));
    }

    private void executeDiscordCommand(final MessageReceivedEvent event, final String message, final Member member) {
        try {
            final String inputMessage = message.substring(CONFIG.discord.prefix.length());
            String memberName = member.getUser().getName();
            String memberId = member.getId();
            DISCORD_LOG.info("{} ({}) executed discord command: {}", memberName, memberId, inputMessage);
            final CommandContext context = DiscordCommandContext.create(inputMessage, event);
            COMMAND.execute(context);
            final MessageCreateData request = commandEmbedOutputToMessage(context);
            if (request != null) {
                DISCORD_LOG.debug("Discord bot response: {}", request.toData().toJson());
                mainChannel.sendMessage(request).queue();
                CommandOutputHelper.logEmbedOutputToTerminal(context.getEmbed());
            }
            if (!context.getMultiLineOutput().isEmpty()) {
                for (final String line : context.getMultiLineOutput()) {
                    mainChannel.sendMessage(line).queue();
                }
                CommandOutputHelper.logMultiLineOutputToTerminal(context);
            }
        } catch (final Exception e) {
            DISCORD_LOG.error("Failed processing discord command: {}", message, e);
        }
    }

    public static String notificationMention() {
        return mentionRole(
            CONFIG.discord.notificationMentionRoleId.isEmpty()
                ? CONFIG.discord.accountOwnerRoleId
                : CONFIG.discord.notificationMentionRoleId
        );
    }

    static String mentionAccountOwner() {
        return mentionRole(CONFIG.discord.accountOwnerRoleId);
    }

    static String mentionRole(final String roleId) {
        try {
            return MentionUtil.forRole(roleId);
        } catch (final NumberFormatException e) {
            DISCORD_LOG.error("Unable to generate mention for role ID: {}", roleId, e);
            return "";
        }
    }

    public void setBotNickname(final String nick) {
        if (!isRunning) return;
        try {
            mainChannel.getGuild().getSelfMember().modifyNickname(nick).complete();
        } catch (final Exception e) {
            DISCORD_LOG.warn("Failed updating bot's nickname. Check that the bot has correct permissions: {}", e.getMessage());
            DISCORD_LOG.debug("Failed updating bot's nickname. Check that the bot has correct permissions", e);
        }
    }

    public void setBotDescription(String description) {
        if (!isRunning) return;
        try {
            jda.updateApplicationDescription(description).complete();
        } catch (final Exception e) {
            DISCORD_LOG.warn("Failed updating bot's description. Check that the bot has correct permissions: {}", e.getMessage());
            DISCORD_LOG.debug("Failed updating bot's description", e);
        }
    }

    private void handleProxyUpdateComplete() {
        CONFIG.discord.isUpdating = false;
        saveConfigAsync();
        sendEmbedMessage(Embed.builder()
                             .title("Update complete!")
                             .description("Current Version: `" + escape(LAUNCH_CONFIG.version) + "`")
                             .successColor());
    }

    public static String escape(String message) {
        return message.replaceAll("_", "\\\\_");
    }

    public synchronized void stop(boolean clearQueue) {
        if (!isRunning) return;
        if (presenceUpdateFuture != null) presenceUpdateFuture.cancel(true);
        if (healthCheckFuture != null) healthCheckFuture.cancel(true);
        try {
            if (clearQueue) {
                jda.shutdownNow();
            } else {
                jda.shutdown();
            }
            jda.awaitShutdown(Duration.ofSeconds(20));
        } catch (final Exception e) {
            DISCORD_LOG.warn("Exception during JDA shutdown", e);
        }
        isRunning = false;
    }

    void updatePresence() {
        if (!isRunning) return;
        try {
//            if (LAUNCH_CONFIG.auto_update) {
//                final AutoUpdater autoUpdater = Proxy.getInstance().getAutoUpdater();
//                if (autoUpdater.getUpdateAvailable()
//                    && ThreadLocalRandom.current().nextDouble() < 0.25
//                ) {
//                    jda.getPresence().setPresence(
//                            OnlineStatus.ONLINE,
//                            Activity.customStatus("Update Available" + autoUpdater.getNewVersion().map(v -> ": " + v).orElse(""))
//                    );
//                    return;
//                }
//            }
            if (MODULE.get(AutoReconnect.class).autoReconnectIsInProgress()) {
                jda.getPresence().setPresence(OnlineStatus.IDLE, Activity.customStatus("AutoReconnecting..."));
                return;
            }
            if (Proxy.getInstance().isInQueue())
                jda.getPresence().setPresence(OnlineStatus.IDLE, Activity.customStatus(queuePositionStr()));
            else if (Proxy.getInstance().isConnected())
                jda.getPresence().setPresence(
                    OnlineStatus.ONLINE,
                    Activity.customStatus((Proxy.getInstance().isOn2b2t() ? "2b2t" : CONFIG.client.server.address)
                                              + " [" + Proxy.getInstance().getOnlineTimeString() + "]"));
            else
                jda.getPresence().setPresence(OnlineStatus.DO_NOT_DISTURB, Activity.customStatus("Disconnected"));
        } catch (final Throwable e) {
            DISCORD_LOG.error("Failed updating discord presence. Check that the bot has correct permissions: {}", e.getMessage());
            DISCORD_LOG.debug("Failed updating discord presence. Check that the bot has correct permissions.", e);
        }
    }

    public void updatePresence(final OnlineStatus onlineStatus, final Activity activity) {
        if (!isRunning) return;
        jda.getPresence().setPresence(onlineStatus, activity);
    }

    public void sendEmbedMessageWithFileAttachment(Embed embed) {
        if (!isRunning) return;
        try {
            var msgBuilder = new MessageCreateBuilder()
                .addEmbeds(embed.toJDAEmbed());
            if (embed.fileAttachment() != null) {
                msgBuilder.addFiles(FileUpload.fromData(new ByteArrayInputStream(embed.fileAttachment.data()), embed.fileAttachment.name()));
            }
            mainChannel.sendMessage(msgBuilder.build()).queue();
            CommandOutputHelper.logEmbedOutputToTerminal(embed);
        } catch (final Exception e) {
            DISCORD_LOG.error("Failed sending discord embed message. Check that the bot has correct permissions: {}", e.getMessage());
            DISCORD_LOG.debug("Failed sending discord embed message. Check that the bot has correct permissions", e);
        }
    }

    public static String queuePositionStr() {
        if (Proxy.getInstance().isPrio())
            return Proxy.getInstance().getQueuePosition() + " / " + Queue.getQueueStatus().prio() + " - ETA: " + Queue.getQueueEta(Proxy.getInstance().getQueuePosition());
        else
            return Proxy.getInstance().getQueuePosition() + " / " + Queue.getQueueStatus().regular() + " - ETA: " + Queue.getQueueEta(Proxy.getInstance().getQueuePosition());
    }

    public static boolean validateButtonInteractionEventFromAccountOwner(final ButtonInteractionEvent event) {
        return Optional.ofNullable(event.getInteraction().getMember())
            .map(m -> m.getRoles().stream()
                .map(ISnowflake::getId)
                .anyMatch(roleId -> roleId.equals(CONFIG.discord.accountOwnerRoleId)))
            .orElse(false);
    }

    public void createClient() {
        if (CONFIG.discord.channelId.isEmpty()) throw new RuntimeException("Discord bot is enabled but channel id is not set");
        if (CONFIG.discord.chatRelay.enable) {
            if (CONFIG.discord.chatRelay.channelId.isEmpty()) throw new RuntimeException("Discord chat relay is enabled and channel id is not set");
            if (CONFIG.discord.channelId.equals(CONFIG.discord.chatRelay.channelId)) throw new RuntimeException("Discord channel id and chat relay channel id cannot be the same");
        }
        if (CONFIG.discord.accountOwnerRoleId.isEmpty()) throw new RuntimeException("Discord account owner role id is not set");
        try {
            Long.parseUnsignedLong(CONFIG.discord.accountOwnerRoleId);
        } catch (final Exception e) {
            throw new RuntimeException("Invalid account owner role ID set: " + CONFIG.discord.accountOwnerRoleId);
        }

        JDABuilder builder = JDABuilder.createLight(
            CONFIG.discord.token,
            asList(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MESSAGES))
            .setActivity(Activity.customStatus("Disconnected"))
            .setStatus(OnlineStatus.DO_NOT_DISTURB)
            .addEventListeners(new SimpleEventBusListener(jdaEventBus));
        this.jda = builder.build();
        try {
            jda.awaitReady();
        } catch (ShutdownException e) {
            if (e.getShutdownReason() == ShutdownReason.DISALLOWED_INTENTS) {
                throw new RuntimeException("You must enable MESSAGE CONTENT INTENT on the Discord developer website: https://i.imgur.com/iznLeDV.png");
            }
            throw e;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        this.mainChannel = Objects.requireNonNull(
            jda.getChannelById(TextChannel.class, CONFIG.discord.channelId),
            "Discord channel not found with ID: " + CONFIG.discord.channelId);
        if (CONFIG.discord.chatRelay.enable) {
            this.relayChannel = Objects.requireNonNull(
                jda.getChannelById(TextChannel.class, CONFIG.discord.chatRelay.channelId),
                "Discord relay channel not found with ID: " + CONFIG.discord.chatRelay.channelId);
        }
    }

    public String extractRelayEmbedSenderUsername(@Nullable final Color color, final String msgContent) {
        final String sender;
        if (color != null && color.equals(Color.MAGENTA)) {
            // extract whisper sender
            sender = msgContent.split("\\*\\*")[1];
        } else if (color != null && color.equals(Color.BLACK)) {
            // extract public chat sender
            sender = msgContent.split("\\*\\*")[1].replace(":", "");
            // todo: we could support death messages here if we remove any bolded discord formatting and feed the message content into the parser
        } else {
            throw new RuntimeException("Unhandled message being replied to, aborting relay");
        }
        return sender;
    }

    private MessageCreateData commandEmbedOutputToMessage(final CommandContext context) {
        var embed = context.getEmbed();
        if (embed.title() == null) return null;
        var msgBuilder = new MessageCreateBuilder()
            .addEmbeds(embed.toJDAEmbed());
        if (embed.fileAttachment() != null) {
            msgBuilder.addFiles(FileUpload.fromData(new ByteArrayInputStream(embed.fileAttachment.data()), embed.fileAttachment.name()));
        }
        return msgBuilder
            .build();
    }

    public static String sanitizeRelayInputMessage(final String input) {
        StringBuilder stringbuilder = new StringBuilder();
        for (char c0 : input.toCharArray()) {
            if (isAllowedChatCharacter(c0)) {
                stringbuilder.append(c0);
            }
        }
        return stringbuilder.toString();
    }

    public void updateProfileImage(final byte[] imageBytes) {
        if (!isRunning) return;
        try {
            jda.getSelfUser().getManager().setAvatar(Icon.from(imageBytes)).complete();
        } catch (final Exception e) {
            DISCORD_LOG.warn("Failed updating discord profile image. Check that the bot has correct permissions: {}", e.getMessage());
            DISCORD_LOG.debug("Failed updating discord profile image. Check that the bot has correct permissions", e);
        }
    }

    public static boolean isAllowedChatCharacter(char c0) {
        return c0 != 167 && c0 >= 32 && c0 != 127;
    }

    Embed getUpdateMessage(final Optional<String> newVersion) {
        String verString = "Current Version: `" + escape(LAUNCH_CONFIG.version) + "`";
        if (newVersion.isPresent()) verString += "\nNew Version: `" + escape(newVersion.get()) + "`";
        var embed = Embed.builder()
            .title("Updating and restarting...")
            .description(verString)
            .primaryColor();
        if (!LAUNCH_CONFIG.auto_update) {
            embed.addField("Info", "`autoUpdate` must be enabled for new updates to apply", false);
        }
        return embed;
    }

    public void sendEmbedMessage(Embed embed) {
        if (isRunning) {
            mainChannel.sendMessage(
                    new MessageCreateBuilder()
                        .addEmbeds(embed.toJDAEmbed())
                        .build())
                .queue();
        }
        CommandOutputHelper.logEmbedOutputToTerminal(embed);
    }

    public void sendEmbedMessage(String message, Embed embed) {
        if (isRunning) {
            mainChannel.sendMessage(
                new MessageCreateBuilder()
                    .setContent(message)
                    .addEmbeds(embed.toJDAEmbed())
                    .build())
                .queue();
        }
        TERMINAL_LOG.info(message);
        CommandOutputHelper.logEmbedOutputToTerminal(embed);
    }

    public void sendRelayEmbedMessage(Embed embed) {
        if (isRunning && CONFIG.discord.chatRelay.enable) {
            relayChannel.sendMessage(
                new MessageCreateBuilder()
                    .addEmbeds(embed.toJDAEmbed())
                    .build())
                .queue();
        }
    }

    public void sendRelayEmbedMessage(String message, Embed embed) {
        if (isRunning && CONFIG.discord.chatRelay.enable) {
            relayChannel.sendMessage(
                new MessageCreateBuilder()
                    .setContent(message)
                    .addEmbeds(embed.toJDAEmbed())
                    .build())
                .queue();
        }
    }

    public void sendMessage(final String message) {
        if (isRunning) {
            mainChannel.sendMessage(
                new MessageCreateBuilder()
                    .setContent(message)
                    .build())
                .queue();
        }
        TERMINAL_LOG.info(message);
    }

    public void sendRelayMessage(final String message) {
        if (isRunning && CONFIG.discord.chatRelay.enable) {
            relayChannel.sendMessage(
                new MessageCreateBuilder()
                    .setContent(message)
                    .build())
                .queue();
        }
    }

    public void sendEmbedMessageWithButtons(String message, Embed embed, List<Button> buttons, Consumer<ButtonInteractionEvent> mapper, Duration timeout) {
        if (isRunning) {
            mainChannel.sendMessage(
                new MessageCreateBuilder()
                    .setEmbeds(embed.toJDAEmbed())
                    .setContent(message)
                    .setActionRow(buttons)
                    .build())
                .queue();
            var buttonIds = buttons.stream().map(ActionComponent::getId).collect(Collectors.toSet());
            jda.listenOnce(ButtonInteractionEvent.class)
                .filter(e -> buttonIds.contains(e.getComponentId()))
                .timeout(timeout)
                .subscribe(mapper);
        }
        TERMINAL_LOG.info(message);
        CommandOutputHelper.logEmbedOutputToTerminal(embed);
    }

    public void sendEmbedMessageWithButtons(Embed embed, List<Button> buttons, Consumer<ButtonInteractionEvent> mapper, Duration timeout) {
        if (isRunning) {
            mainChannel.sendMessage(
                new MessageCreateBuilder()
                    .setEmbeds(embed.toJDAEmbed())
                    .setActionRow(buttons)
                    .build())
                .queue();
            var buttonIds = buttons.stream().map(ActionComponent::getId).collect(Collectors.toSet());
            jda.listenOnce(ButtonInteractionEvent.class)
                .filter(e -> buttonIds.contains(e.getComponentId()))
                .timeout(timeout)
                .subscribe(mapper);
        }
        CommandOutputHelper.logEmbedOutputToTerminal(embed);
    }
}
