package io.ebean.lettuce.topic;

import io.avaje.applog.AppLog;
import io.lettuce.core.RedisChannelHandler;
import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;

import java.net.SocketAddress;
import java.util.Timer;
import java.util.TimerTask;

import static java.lang.System.Logger.Level.*;

/**
 * Subscribe to redis topic listening for changes.
 * <p>
 * Handles reconnection to redis when the connection is lost and
 * notification of when reconnection takes place.
 * </p>
 */
public final class DaemonTopicRunner implements RedisConnectionStateListener {

    private static final System.Logger log = AppLog.getLogger(DaemonTopicRunner.class);

    private static final long reconnectWaitMillis = 1000;

    private final StatefulRedisPubSubConnection<byte[], byte[]> redisPubSubConnection;
    private final DaemonTopic daemonTopic;

    public DaemonTopicRunner(StatefulRedisPubSubConnection<byte[], byte[]> redisPubSubConnection, DaemonTopic daemonTopic) {
        this.redisPubSubConnection = redisPubSubConnection;
        this.daemonTopic = daemonTopic;
    }

    public void run() {
        new Thread(this::attemptConnections, "redis-sub").start();
    }

    private void attemptConnections() {
        Timer reloadTimer = new Timer("redis-sub-notify");
        ReloadNotifyTask notifyTask = null;
        int attempts = 1;
        while (true) {
            if (notifyTask != null) {
                // we didn't successfully re-connect to redis
                notifyTask.cancel();
            }
            notifyTask = new ReloadNotifyTask();
            reloadTimer.schedule(notifyTask, reconnectWaitMillis + 500);
            attempts++;
            try {
                subscribe();
            } catch (Exception e) {
                log.log(DEBUG, "... redis subscribe connection attempt:{0} failed:{1}", attempts, e.getMessage());
                try {
                    // wait a little before retrying
                    Thread.sleep(reconnectWaitMillis);
                } catch (InterruptedException e1) {
                    Thread.currentThread().interrupt();
                    log.log(WARNING, "Interrupted redis re-connection wait", e1);
                }
            }
        }
    }

    /**
     * Subscribe and block when successful.
     */
    private void subscribe() {


        try {
            RedisPubSubCommands<byte[], byte[]> commands = redisPubSubConnection.sync();

            commands.echo("hi".getBytes());
            daemonTopic.subscribe(redisPubSubConnection);

        } catch (Exception e) {
            log.log(ERROR, "Lost connection to topic, starting re-connection loop", e);
            attemptConnections();
        }
    }

    @Override
    public void onRedisConnected(RedisChannelHandler<?, ?> connection, SocketAddress socketAddress) {

        log.log(DEBUG, "Connected to redis channel:{0}", socketAddress);

    }

    @Override
    public void onRedisDisconnected(RedisChannelHandler<?, ?> connection) {
        log.log(DEBUG, "Disconnected from redis channel");
    }

    private class ReloadNotifyTask extends TimerTask {
        @Override
        public void run() {
            daemonTopic.notifyConnected();
        }
    }
}
