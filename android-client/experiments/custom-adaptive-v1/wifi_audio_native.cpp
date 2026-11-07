#include <jni.h>
#include <android/log.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include<string>
#include <pthread.h>
#include <sched.h>
#include <atomic>
#include <mutex>
#include <condition_variable>
#include <chrono>
#include <cstdint>
#include <new>
#include <algorithm>
#include <vector>

#define LOG_TAG "WiFiAudioNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)



// Global state for optimized streaming
static std::atomic<bool> streaming_active{false};
static std::atomic<bool> g_inited{false};
static std::atomic<bool> g_force_disconnected{false};

int tcp_socket = -1;
// Remember the OS-default UDP receive queue for this socket. High/Medium may
// enlarge it; switching back to Golden restores the untouched baseline.
static int original_socket_receive_buffer_bytes = 0;
static void remember_original_socket_receive_buffer() {
    if (tcp_socket < 0) return;
    int value = 0;
    socklen_t valueLength = sizeof(value);
    if (getsockopt(tcp_socket, SOL_SOCKET, SO_RCVBUF, &value, &valueLength) == 0 && value > 0) {
        original_socket_receive_buffer_bytes = value;
    }
}
static void restore_original_socket_receive_buffer() {
    if (tcp_socket < 0 || original_socket_receive_buffer_bytes <= 0) return;
    setsockopt(tcp_socket, SOL_SOCKET, SO_RCVBUF,
               &original_socket_receive_buffer_bytes,
               sizeof(original_socket_receive_buffer_bytes));
}
static std::string g_preferred_local_ip;
static std::mutex g_preferred_local_ip_mutex;
static pthread_t streaming_thread = 0;
static struct sockaddr_in server_addr;

// One native lifecycle lock protects socket recreation, thread join, and
// cleanup from concurrent Service/network/coroutine teardown paths.
static std::mutex native_lifecycle_mutex;

// Enhanced audio parameters for multi-backend compatibility
static const int SAMPLE_RATE = 48000;
static const int CHANNELS = 1; // Mono  - must match server exactly
static const int BASE_BUFFER_SIZE = 480; // 🎯 Exactly 10ms at 44.1kHz
static const int MAX_BUFFER_SIZE = 480; // No dynamic buffer sizing needed for UDP
static const int  BYTES_PER_SAMPLE = 2; // 16-bit MONO
static const int PAYLOAD_BYTES = BASE_BUFFER_SIZE * BYTES_PER_SAMPLE;  // 960  bytes
static const int HEADER_BYTES     = 4;             // uint32 BE sequence
static const int MAX_RETRY_ATTEMPTS = 5; // More retry attempts for stability

// CRITICAL: Ring Buffer Implementation classic bounded circular buffer with proper locking (mono 16-bit)
static const size_t RING_BUFFER_SIZE = 144000; // Existing adaptive profiles: 3000ms active capacity.
static const size_t GOLDEN_ULTRA_LOW_RING_BUFFER_SIZE = 48000; // Golden profile: 1000ms at 48kHz mono.
static const size_t LOW_BUFFER_RING_BUFFER_SIZE = 72000; // Low: 1500ms bounded ring for lip-sync-focused catch-up.
static const size_t MEDIUM_BUFFER_RING_BUFFER_SIZE = 96000; // Medium: 2000ms headroom for balanced jitter tolerance.
static const size_t HIGH_BUFFER_RING_BUFFER_SIZE = 480000; // High profile: 10000ms audio-preserving capacity.
static const size_t RING_BUFFER_ALLOCATION_SIZE = HIGH_BUFFER_RING_BUFFER_SIZE;
static std::atomic<bool> golden_ultra_low_profile{false};
static std::atomic<bool> low_buffer_profile{false};
static std::atomic<bool> medium_buffer_profile{false};
static std::atomic<bool> high_buffer_profile{false};
static std::atomic<bool> custom_buffer_profile{false};
static std::atomic<int> custom_buffer_target_ms{500};
static std::atomic<uint64_t> custom_buffer_ring_drops{0};
static size_t custom_ring_capacity_for_target_ms(int target_ms) {
    const int capacity_ms = std::max(1500, std::min(3000, target_ms + 1000));
    return static_cast<size_t>(SAMPLE_RATE) * static_cast<size_t>(capacity_ms) / 1000U;
}
static size_t active_ring_buffer_capacity = RING_BUFFER_SIZE; // Protected by ring_buffer_mutex.
static int16_t* ring_buffer = nullptr;
static size_t ring_write_pos = 0;
static size_t ring_read_pos = 0;
static size_t ring_used = 0;

static std::mutex ring_buffer_mutex;
static std::condition_variable ring_not_empty;
static std::condition_variable ring_not_full;

// Add these global variables for jitter buffer
static int current_jitter_buffer_ms = 120; // Default 120ms
static std::atomic<int> buffer_underruns{0};
static std::atomic<int> packets_received{0};
static std::atomic<int> packets_dropped{0};

// Enhanced streaming buffer management with adaptive sizing
static uint8_t *stream_buffer = nullptr;
static size_t buffer_size = 0;
static size_t current_buffer_size = BASE_BUFFER_SIZE;
static std::atomic<int> connection_health{100};
static std::atomic<int> adaptive_buffer_level{2}; // 0=minimal, 1=normal, 2=enhanced, 3=maximum

// Server backend detection and adaptation
static std::atomic<int> server_backend_type{0}; // 0=unknown, 1=pyaudiowpatch, 2=sounddevice, 3=soundcard
static std::atomic<bool> multi_backend_server{false};
static std::atomic<int> packet_loss_count{0};
static std::atomic<int> successful_packets{0};

// Declaration for nativeSendHandshake from network_handler.cpp
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_wifiaudiospeaker_MainActivity_nativeSendHandshake(JNIEnv *env, jclass clazz,
                                                                   jstring manufacturer,
                                                                   jstring model, jstring device);

// CRITICAL: Ring Buffer Functions
static bool initialize_ring_buffer() {

    std::lock_guard<std::mutex> lock(ring_buffer_mutex);

    if (ring_buffer) return true;

    ring_buffer = new(std::nothrow) int16_t[RING_BUFFER_ALLOCATION_SIZE];

    if (!ring_buffer) {
        LOGE("Failed to allocate ring buffer"); return false;
    }

    active_ring_buffer_capacity = golden_ultra_low_profile.load()
            ? GOLDEN_ULTRA_LOW_RING_BUFFER_SIZE
            : (high_buffer_profile.load() ? HIGH_BUFFER_RING_BUFFER_SIZE
               : (medium_buffer_profile.load() ? MEDIUM_BUFFER_RING_BUFFER_SIZE
                  : (low_buffer_profile.load() ? LOW_BUFFER_RING_BUFFER_SIZE
                     : (custom_buffer_profile.load() ? custom_ring_capacity_for_target_ms(custom_buffer_target_ms.load()) : RING_BUFFER_SIZE))));
    memset(ring_buffer, 0, RING_BUFFER_ALLOCATION_SIZE * sizeof(int16_t));
    ring_write_pos = ring_read_pos = ring_used = 0;

    const char *ringProfile = golden_ultra_low_profile.load() ? "Golden Ultra Low"
            : (high_buffer_profile.load() ? "High continuity"
               : (medium_buffer_profile.load() ? "Medium balanced"
                  : (low_buffer_profile.load() ? "Low lip-sync"
                     : (custom_buffer_profile.load() ? "Custom adaptive" : "adaptive"))));
    LOGI("Ring buffer initialized: active=%zu / allocated=%zu samples (%s profile)",
         active_ring_buffer_capacity, RING_BUFFER_ALLOCATION_SIZE, ringProfile);
    return true;
}

static void cleanup_ring_buffer() {

    std::lock_guard<std::mutex> lock(ring_buffer_mutex);

    if (ring_buffer) {
        delete[] ring_buffer; ring_buffer = nullptr;
    }

    ring_write_pos = ring_read_pos = ring_used = 0;
    LOGI("Ring buffer cleaned up");
}

static size_t write_to_ring_buffer(const int16_t* data, size_t samples) {
    std::unique_lock<std::mutex> lk(ring_buffer_mutex);
    size_t written = 0;
    size_t capacity = active_ring_buffer_capacity;
    while (written < samples && streaming_active.load()) {
        capacity = active_ring_buffer_capacity;
        while (ring_used >= capacity && streaming_active.load()) {
            if (high_buffer_profile.load() && !golden_ultra_low_profile.load()) {
                // High is continuity-first: apply back-pressure and retain queued PCM.
                ring_not_full.wait(lk);
                capacity = active_ring_buffer_capacity;
            } else if (low_buffer_profile.load() && !golden_ultra_low_profile.load()) {
                // Low preserves the timeline through brief bursts, then evicts only
                // one 10ms frame after a 30ms full-ring stall to prevent runaway latency.
                const auto waitStatus = ring_not_full.wait_for(lk, std::chrono::milliseconds(30));
                capacity = active_ring_buffer_capacity;
                if (waitStatus != std::cv_status::timeout ||
                    !low_buffer_profile.load() || medium_buffer_profile.load() ||
                    high_buffer_profile.load() || golden_ultra_low_profile.load() || ring_used < capacity) {
                    continue;
                }
                const size_t drop_samples = std::min(samples, std::min<size_t>(BASE_BUFFER_SIZE, ring_used));
                ring_read_pos = (ring_read_pos + drop_samples) % capacity;
                ring_used -= drop_samples;
                packets_dropped++;
                static std::atomic<int64_t> lastLowOverflowLogMs{0};
                const int64_t nowMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now().time_since_epoch()).count();
                int64_t previous = lastLowOverflowLogMs.load();
                if (nowMs - previous >= 5000 && lastLowOverflowLogMs.compare_exchange_strong(previous, nowMs)) {
                    LOGE("Low ring remained full after 30ms; evicted one bounded 10ms frame (%zu samples)", drop_samples);
                }
                break;
            } else if (custom_buffer_profile.load() && !golden_ultra_low_profile.load()) {
                // Custom is continuity-first within the user's selected reserve.
                // Wait for a longer capture/reader scheduling gap, then evict only
                // one 10ms oldest frame so latency cannot grow without a bound.
                const auto waitStatus = ring_not_full.wait_for(lk, std::chrono::milliseconds(60));
                capacity = active_ring_buffer_capacity;
                if (waitStatus != std::cv_status::timeout ||
                    !custom_buffer_profile.load() || golden_ultra_low_profile.load() ||
                    high_buffer_profile.load() || medium_buffer_profile.load() ||
                    low_buffer_profile.load() || ring_used < capacity) {
                    continue;
                }
                const size_t drop_samples = std::min<size_t>(BASE_BUFFER_SIZE, ring_used);
                ring_read_pos = (ring_read_pos + drop_samples) % capacity;
                ring_used -= drop_samples;
                packets_dropped++;
                const uint64_t events = ++custom_buffer_ring_drops;
                static std::atomic<int64_t> lastCustomOverflowLogMs{0};
                const int64_t nowMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now().time_since_epoch()).count();
                int64_t previous = lastCustomOverflowLogMs.load();
                if (nowMs - previous >= 5000 && lastCustomOverflowLogMs.compare_exchange_strong(previous, nowMs)) {
                    LOGE("Custom ring full after 60ms; evicted one 10ms frame (events=%llu, target=%dms)",
                         static_cast<unsigned long long>(events), custom_buffer_target_ms.load());
                }
                break;
            } else if (medium_buffer_profile.load() && !golden_ultra_low_profile.load()) {
                // Medium tolerates a short producer stall before its bounded overflow
                // fallback; this is intentionally less aggressive than the 15ms adaptive path.
                const auto waitStatus = ring_not_full.wait_for(lk, std::chrono::milliseconds(60));
                capacity = active_ring_buffer_capacity;
                // A profile transition may happen while the condition variable
                // releases the mutex. Re-evaluate the new policy before evicting PCM.
                if (waitStatus != std::cv_status::timeout ||
                    !medium_buffer_profile.load() || high_buffer_profile.load() ||
                    golden_ultra_low_profile.load() || ring_used < capacity) {
                    continue;
                }
                size_t drop_samples = std::min(samples, ring_used);
                ring_read_pos = (ring_read_pos + drop_samples) % capacity;
                ring_used -= drop_samples;
                // Count this explicitly: the incoming UDP packet is still written,
                // but the oldest queued PCM was evicted to recover from sustained overload.
                packets_dropped++;
                static std::atomic<int64_t> lastMediumOverflowLogMs{0};
                const int64_t nowMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now().time_since_epoch()).count();
                int64_t previous = lastMediumOverflowLogMs.load();
                if (nowMs - previous >= 5000 && lastMediumOverflowLogMs.compare_exchange_strong(previous, nowMs)) {
                    LOGE("Medium ring remained full after 60ms; evicted %zu oldest samples (repeat log limited to 5s)", drop_samples);
                }
                break;
            } else if (ring_not_full.wait_for(lk, std::chrono::milliseconds(15)) == std::cv_status::timeout) {
                // Existing adaptive/Golden fallback policy is deliberately unchanged.
                size_t drop_samples = std::min(samples, ring_used);
                ring_read_pos = (ring_read_pos + drop_samples) % capacity;
                ring_used -= drop_samples;
                static std::atomic<int64_t> lastOverflowLogMs{0};
                static std::atomic<uint64_t> overflowEvents{0};
                const uint64_t totalEvents = ++overflowEvents;
                const int64_t nowMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now().time_since_epoch()).count();
                int64_t previous = lastOverflowLogMs.load();
                if (nowMs - previous >= 5000 && lastOverflowLogMs.compare_exchange_strong(previous, nowMs)) {
                    LOGE("Adaptive ring overflow: dropped %zu samples (events=%llu; repeat log limited to 5s)",
                         drop_samples, static_cast<unsigned long long>(totalEvents));
                }
                break;
            }
        }

        if (!streaming_active.load()) break;

        size_t space = capacity - ring_used;
        size_t can = std::min(samples - written, space);
        if (can == 0) continue;
        size_t first = std::min(can, capacity - ring_write_pos);

        memcpy(ring_buffer + ring_write_pos, data + written, first * sizeof(int16_t));

        if (can > first) {
            size_t remain = can - first;
            memcpy(ring_buffer, data + written + first, remain * sizeof(int16_t));
        }

        ring_write_pos = (ring_write_pos + can) % capacity;
        ring_used += can;
        written += can;
        ring_not_empty.notify_one();
    }
    if (written < samples) {
        packets_dropped++;
    }
    else {
        packets_received++;
    }
    return written;
}

static size_t read_from_ring_buffer(
        int16_t* data,
        size_t samples
) {

    std::unique_lock<std::mutex> lk(ring_buffer_mutex);

    if (!ring_buffer || samples == 0) {
        return 0;
    }

    // Golden keeps its original 10ms read. Low waits 30ms, Medium 35ms,
    // High 200ms; Custom gets a modest 20ms gap tolerance while its main
    // jitter reserve lives in the native ring rather than the hardware track.
    const auto read_wait = golden_ultra_low_profile.load()
            ? std::chrono::milliseconds(10)
            : (high_buffer_profile.load() ? std::chrono::milliseconds(200)
               : (medium_buffer_profile.load() ? std::chrono::milliseconds(35)
                  : (low_buffer_profile.load() ? std::chrono::milliseconds(30)
                     : (custom_buffer_profile.load() ? std::chrono::milliseconds(20)
                                                      : std::chrono::milliseconds(15)))));
    const bool ready = ring_not_empty.wait_for(
            lk,
            read_wait,
            [&] {
                return ring_used >= samples ||
                       !streaming_active.load();
            }
    );

    if (!streaming_active.load()) {
        return 0;
    }

    // No complete block arrived within the wait window.
    // Return 0 so Java can insert one full silence block. The JNI caller owns
    // underrun accounting; incrementing here as well counted each event twice.
    if (!ready || ring_used < samples) {
        return 0;
    }

    const size_t first =
            std::min(samples, active_ring_buffer_capacity - ring_read_pos);

    memcpy(
            data,
            ring_buffer + ring_read_pos,
            first * sizeof(int16_t)
    );

    if (first < samples) {
        const size_t remain = samples - first;

        memcpy(
                data + first,
                ring_buffer,
                remain * sizeof(int16_t)
        );
    }

    ring_read_pos =
            (ring_read_pos + samples) % active_ring_buffer_capacity;

    ring_used -= samples;

    ring_not_full.notify_one();

    return samples;
}


static void await_preroll_samples(size_t target_samples);


// Streaming thread function with proper ring buffer integration aNd data validation:
void *streaming_thread_func(void *arg) {
    LOGI(" 🎧 🎶  Audio streaming thread started ");
    LOGI("📥 EXPECTED FORMAT: %dHz MONO 16-bit", SAMPLE_RATE);
    LOGI("📥 Frame Size: %d samples (%d bytes)", BASE_BUFFER_SIZE, PAYLOAD_BYTES);

    // Set high priority for audio thread
    struct sched_param param;
    param.sched_priority = sched_get_priority_max(SCHED_FIFO);
    if (pthread_setschedparam(pthread_self(), SCHED_FIFO, &param) != 0) {
        LOGI("Could not set real-time priority, continuing with normal priority");
    }


    if (!initialize_ring_buffer()) {
        LOGE(" Failed to initialize ring buffer in streaming thread ");
        return nullptr;
    }

    // 🎯 UDP: Allocate a single buffer for the FULL Datagram (Header + Payload combined)
    const int DATAGRAM_SIZE = HEADER_BYTES + PAYLOAD_BYTES;
    uint8_t* datagram_buffer = new(std::nothrow) uint8_t[DATAGRAM_SIZE];

    if (!datagram_buffer) {
        LOGE("Failed to allocate UDP datagram buffer");
        return nullptr;
    }

    int consecutive_failures = 0;
    const int max_failures = 15; // UDP drops packets normally, allow more failures before quitting
    int frames_received = 0;
    uint32_t last_seq = 0;

    while (streaming_active.load()) {

        // 🎯 UDP: Read the entire datagram in one single operation. (No MSG_WAITALL needed)
        ssize_t bytes_read = recv(tcp_socket, datagram_buffer, DATAGRAM_SIZE, 0);

        if (bytes_read == DATAGRAM_SIZE) {
            // Success! Extract payload directly skipping the 4-byte header
            uint8_t* payload_ptr = datagram_buffer + HEADER_BYTES;

            uint32_t seq = ((uint32_t)datagram_buffer[0] << 24) | ((uint32_t)datagram_buffer[1] << 16) | ((uint32_t)datagram_buffer[2] << 8) | (uint32_t)datagram_buffer[3];
            if (frames_received > 0 && seq > last_seq + 1) {
                uint32_t drops = seq - last_seq - 1;
                if (drops < 5000) { // Ignore massive jumps (e.g. server restart)
                    packets_dropped.fetch_add(static_cast<int>(drops));
                    packet_loss_count.fetch_add(drops);
                }
            }
            last_seq = seq;

            consecutive_failures = 0;
            frames_received++;
            successful_packets.fetch_add(1);

            // Convert bytes to samples and write to ring buffer
            size_t samples_received = PAYLOAD_BYTES / BYTES_PER_SAMPLE;
            size_t samples_written = write_to_ring_buffer((int16_t*)payload_ptr, samples_received);

            // Diagnostic-only summary every 10000 frames (~100s); keep per-packet
            // logging out of Logcat so warnings/state transitions remain visible.
            if (frames_received % 10000 == 0) {
                __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG,
                    "UDP receive summary: frames=%d, payload=%d bytes",
                    frames_received, PAYLOAD_BYTES);
            }

        } else if (!streaming_active.load()) {
            break;
        }  else if (bytes_read > 0 && bytes_read < DATAGRAM_SIZE) {
            std::string control(reinterpret_cast<char *>(datagram_buffer), bytes_read);
            if (control.find("SERVER_FORCE_DISCONNECT") != std::string::npos) {
                LOGE("⚠️ SERVER_FORCE_DISCONNECT received from server - stopping cleanly");
                g_force_disconnected.store(true);
                streaming_active.store(false);
                break;
            }

            // 🛡️ Ignore late SERVER_ACK or HEARTBEAT control packets without penalizing stream health
            if (control.find("SERVER_ACK") != std::string::npos || control.find("CLIENT_HEARTBEAT") != std::string::npos) {
                continue;
            }

            static std::atomic<int64_t> lastShortPacketLogMs{0};
            const int64_t shortNowMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now().time_since_epoch()).count();
            int64_t shortPrevious = lastShortPacketLogMs.load();
            if (shortNowMs - shortPrevious >= 5000 && lastShortPacketLogMs.compare_exchange_strong(shortPrevious, shortNowMs)) {
                LOGE("UDP short packet: got %zd bytes, expected %d (repeat diagnostics limited to 5s)", bytes_read, DATAGRAM_SIZE);
            }
            consecutive_failures++;
            packet_loss_count.fetch_add(1);
            connection_health.store(std::max(0, connection_health.load() - 3));
        } else if (bytes_read < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == ETIMEDOUT || errno == EINTR)) {
            // Transient socket timeout or interruption; keep the stream alive.
            continue;
        } else {
            static std::atomic<int64_t> lastReceiveErrorLogMs{0};
            const int64_t errorNowMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now().time_since_epoch()).count();
            int64_t errorPrevious = lastReceiveErrorLogMs.load();
            if (errorNowMs - errorPrevious >= 5000 && lastReceiveErrorLogMs.compare_exchange_strong(errorPrevious, errorNowMs)) {
                LOGE("UDP receive error: got %zd bytes, expected %d (repeat diagnostics limited to 5s)", bytes_read, DATAGRAM_SIZE);
            }
            consecutive_failures++;
            packet_loss_count.fetch_add(1);
            connection_health.store(std::max(0, connection_health.load() - 3));
        }

        // FIXED: Check for too many consecutive failures
        if (consecutive_failures >= max_failures) {
            LOGE("Too many consecutive failures (%d), stopping stream", consecutive_failures);
            streaming_active.store(false);
            break;
        }

        // FIXED: Brief delay on error to prevent CPU spinning
        if (bytes_read <= 0 && streaming_active.load()) {
            int current_health = connection_health.load();
            connection_health.store(std::max(0, current_health - 3));

            // Progressive backoff: more failures = longer delay
            int sleep_time = consecutive_failures * 250 + 500; // 500ms to 1750ms
            usleep(sleep_time);
        }

        // FIXED: Update server backend detection periodically
        if (frames_received > 0 && frames_received % 50 == 0) {
            int health = connection_health.load();
            int success_rate = (successful_packets.load() * 100) /
                               (successful_packets.load() + packet_loss_count.load() + 1);

            // Classify server backend based on performance
            if (success_rate > 98 && health > 95) {
                server_backend_type.store(1); // PyAudioWPatch - best performance
            } else if (success_rate > 90 && health > 80) {
                server_backend_type.store(2); // SoundDevice - good performance
            } else if (success_rate > 70) {
                server_backend_type.store(3); // SoundCard - basic performance
            }

            // Detect if server supports multi-backend features
            if (current_buffer_size == MAX_BUFFER_SIZE && health > 60) {
                multi_backend_server.store(true);
            }
        }
    } // FIXED: Properly close the while loop

    // FIXED: Cleanup code moved outside the loop
    delete[] datagram_buffer;
    LOGI(" 🎧  Audio streaming thread ended - received %d frames", frames_received);
    return nullptr;
}



// Helper: busy-wait small pre-roll before Java starts pulling
static void await_preroll_samples(size_t target_samples) {
    // ~20ms (960 samples) default is a good starting point
    for (int i = 0; i < 200; ++i) { // up to ~200ms guard
        {
            std::lock_guard<std::mutex> lk(ring_buffer_mutex);
            if (ring_used >= target_samples) return;
        }
        usleep(1000); // 1ms step
    }
}

extern "C" {

// Service wrapper functions
JNIEXPORT jstring JNICALL
Java_com_example_wifiaudiospeaker_AudioStreamingService_nativeServiceSendHandshake(JNIEnv *env, jclass clazz,
                                                                                   jstring manufacturer,
                                                                                   jstring model, jstring device) {
    LOGI("AudioStreamingService calling nativeSendHandshake");
    return Java_com_example_wifiaudiospeaker_MainActivity_nativeSendHandshake(env, clazz, manufacturer, model, device);
}

JNIEXPORT jboolean JNICALL
Java_com_example_wifiaudiospeaker_MainActivity_nativeInit(JNIEnv *env, jclass clazz) {
    LOGI("Initializing native audio streaming");

    if (g_inited.load()) {
        LOGI("Already initialized");
        return JNI_TRUE;
    }

    if (!initialize_ring_buffer()) {
        return JNI_FALSE;
    }

    
    if (tcp_socket >= 0) {
        close(tcp_socket);
        tcp_socket = -1;
    }

    tcp_socket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (tcp_socket < 0) {
        LOGE("Failed to create UDP socket");
        return JNI_FALSE;
    }

    int flags = fcntl(tcp_socket, F_GETFL, 0);
    fcntl(tcp_socket, F_SETFL, flags | O_NONBLOCK);

    struct timeval timeout;
    timeout.tv_sec = 0;
    timeout.tv_usec = 100000; // 100ms: fast native shutdown/restart without a 2s join stall
    setsockopt(tcp_socket, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    remember_original_socket_receive_buffer();

    //streaming_active.store(true);
    g_force_disconnected.store(false);
    connection_health.store(100);
    server_backend_type.store(0);
    multi_backend_server.store(false);
    packet_loss_count.store(0);
    successful_packets.store(0);
    adaptive_buffer_level.store(2);
    g_inited.store(true);
    LOGI("Native initialization complete");
    return JNI_TRUE;
}


static bool recreate_udp_socket_for_current_network() {

    if (tcp_socket >= 0) {
        close(tcp_socket);
        tcp_socket = -1;
    }

    tcp_socket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);

    if (tcp_socket < 0) {
        LOGE(
                "Failed to recreate UDP socket for current network: %s",
                strerror(errno)
        );
        return false;
    }

    int flags = fcntl(tcp_socket, F_GETFL, 0);

    if (flags >= 0) {
        fcntl(
                tcp_socket,
                F_SETFL,
                flags | O_NONBLOCK
        );
    }

    struct timeval timeout;
    timeout.tv_sec = 0;
    timeout.tv_usec = 100000; // 100ms: route-change teardown must not block for 2s

    setsockopt(
            tcp_socket,
            SOL_SOCKET,
            SO_RCVTIMEO,
            &timeout,
            sizeof(timeout)
    );
    remember_original_socket_receive_buffer();

    LOGI(
            "Fresh UDP socket created for current network route"
    );

    return true;
}



static bool bind_socket_to_preferred_local_ip() {
    std::string local_ip;
    {
        std::lock_guard<std::mutex> lock(g_preferred_local_ip_mutex);
        local_ip = g_preferred_local_ip;
    }
    if (local_ip.empty() || tcp_socket < 0) return true;

    struct sockaddr_in local_addr{};
    local_addr.sin_family = AF_INET;
    local_addr.sin_port = htons(0);
    if (inet_pton(AF_INET, local_ip.c_str(), &local_addr.sin_addr) <= 0) {
        LOGE("Invalid preferred local IP: %s", local_ip.c_str());
        return false;
    }

    if (bind(tcp_socket, reinterpret_cast<struct sockaddr*>(&local_addr), sizeof(local_addr)) != 0) {
        LOGE("Could not bind UDP socket to preferred local IP %s: %s", local_ip.c_str(), strerror(errno));
        return false;
    }

    LOGI("UDP socket bound to preferred local IP %s", local_ip.c_str());
    return true;
}

JNIEXPORT jboolean JNICALL
Java_com_example_wifiaudiospeaker_AudioStreamingService_nativeServiceSetPreferredLocalIp(
        JNIEnv *env, jclass clazz, jstring ip) {
    const char *value = ip ? env->GetStringUTFChars(ip, nullptr) : nullptr;
    {
        std::lock_guard<std::mutex> lock(g_preferred_local_ip_mutex);
        g_preferred_local_ip = value ? value : "";
    }
    if (value) env->ReleaseStringUTFChars(ip, value);
    LOGI("Preferred native local IP updated: %s", g_preferred_local_ip.c_str());
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_wifiaudiospeaker_MainActivity_nativeConnect(JNIEnv *env, jclass clazz,
                                                             jstring ip, jint port) {
    std::lock_guard<std::mutex> lifecycle_lock(native_lifecycle_mutex);
    g_force_disconnected.store(false);
    const char *server_ip = env->GetStringUTFChars(ip, nullptr);
    LOGI("Connecting to server %s:%d", server_ip, port);

    memset(&server_addr, 0, sizeof(server_addr));
    server_addr.sin_family = AF_INET;
    server_addr.sin_port = htons(port);

    if (inet_pton(AF_INET, server_ip, &server_addr.sin_addr) <= 0) {
        LOGE("Invalid server IP address: %s", server_ip);
        std::string ip_for_log(server_ip);
        env->ReleaseStringUTFChars(ip, server_ip);
        return JNI_FALSE;
    }

     // Process-wide network binding does NOT migrate an already-created
     // UDP socket to the replacement interface.
     //
     // Therefore:
     //   • first connection -> normal native initialization
     //   • reconnect after stop/route change -> fresh UDP socket
     //
    if (!g_inited.load()) {

        if (!Java_com_example_wifiaudiospeaker_MainActivity_nativeInit(env,clazz)) {

            LOGE("Failed to initialize native UDP stack before connect");

            env->ReleaseStringUTFChars(
                    ip,
                    server_ip);

            return JNI_FALSE;
        }

    } else if (!streaming_active.load()) {

        if (!recreate_udp_socket_for_current_network()) {

            env->ReleaseStringUTFChars(
                    ip,
                    server_ip
            );

            return JNI_FALSE;
        }

    } else if (tcp_socket < 0) {

        LOGE(
                "Native socket missing before connect while streaming is active"
        );

        env->ReleaseStringUTFChars(
                ip,
                server_ip
        );

        return JNI_FALSE;
    }

    server_addr.sin_family = AF_INET;
    server_addr.sin_port = htons(port);

    inet_pton(
            AF_INET,
            server_ip,
            &server_addr.sin_addr
    );

    // If the Service supplied the exact local interface address used by
    // discovery, pin the new UDP socket to it before connect(). This prevents
    // Android from sending a SoftAP/RNDIS stream through cellular/default Wi-Fi.
    if (!bind_socket_to_preferred_local_ip()) {
        LOGE("Preferred local-interface bind failed; aborting native connect");
        std::string ip_for_log(server_ip);
        env->ReleaseStringUTFChars(ip, server_ip);
        return JNI_FALSE;
    }

// UDP connect() selects the destination for send()/recv().
    int result = connect(
            tcp_socket,
            (struct sockaddr *) &server_addr,
            sizeof(server_addr)
    );

    std::string ip_for_log(server_ip);
    env->ReleaseStringUTFChars(ip, server_ip);

    if (result == 0) {
        LOGI("UDP Target locked successfully to %s:%d", ip_for_log.c_str(), port);

        // Switch to blocking for stable reads
        int flags = fcntl(tcp_socket, F_GETFL, 0);
        fcntl(tcp_socket, F_SETFL, flags & ~O_NONBLOCK);

        return JNI_TRUE;
    } else {
        LOGE("Failed to set UDP target immediately: %s", strerror(errno));
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_example_wifiaudiospeaker_AudioStreamingService_nativeServiceSetJitterBuffer(
        JNIEnv *env, jclass clazz, jint bufferMs) {
    current_jitter_buffer_ms = (int)bufferMs;
    LOGI("Jitter buffer set to %d ms", current_jitter_buffer_ms);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_wifiaudiospeaker_AudioStreamingService_nativeServiceSetUltraLowProfile(
        JNIEnv *env, jclass clazz, jboolean enabled) {
    const bool requested = (enabled == JNI_TRUE);
    const size_t requested_capacity = requested
            ? GOLDEN_ULTRA_LOW_RING_BUFFER_SIZE
            : (high_buffer_profile.load() ? HIGH_BUFFER_RING_BUFFER_SIZE
               : (medium_buffer_profile.load() ? MEDIUM_BUFFER_RING_BUFFER_SIZE
                  : (low_buffer_profile.load() ? LOW_BUFFER_RING_BUFFER_SIZE : RING_BUFFER_SIZE)));

    {
        std::lock_guard<std::mutex> lock(ring_buffer_mutex);
        const bool old_profile = golden_ultra_low_profile.load();
        if (old_profile == requested && active_ring_buffer_capacity == requested_capacity) {
            return JNI_TRUE;
        }

        if (requested) {
            high_buffer_profile.store(false);
            medium_buffer_profile.store(false);
            low_buffer_profile.store(false);
            custom_buffer_profile.store(false);
        }
        golden_ultra_low_profile.store(requested);
        active_ring_buffer_capacity = requested_capacity;

        // Treat a profile switch as a clean playback boundary. Do not carry
        // queued PCM written under the previous ring capacity/trim policy.
        ring_write_pos = 0;
        ring_read_pos = 0;
        ring_used = 0;
        if (ring_buffer) {
            memset(ring_buffer, 0, RING_BUFFER_ALLOCATION_SIZE * sizeof(int16_t));
        }
    }
    ring_not_full.notify_all();
    ring_not_empty.notify_all();

    if (requested) {
        // High/Medium may have enlarged SO_RCVBUF on this live socket. Golden
        // uses the OS-default queue exactly as before those profiles existed.
        restore_original_socket_receive_buffer();
    }

    const char *profileName = requested ? "golden_ultra_low_v1"
            : (high_buffer_profile.load() ? "high_continuity_v1"
               : (medium_buffer_profile.load() ? "medium_balanced_v1" : "adaptive_v2"));
    LOGI("🎚️ Native streaming profile=%s; active ring capacity=%zu samples",
         profileName, requested_capacity);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_wifiaudiospeaker_AudioStreamingService_nativeServiceSetHighBufferProfile(
        JNIEnv *env, jclass clazz, jboolean enabled) {
    const bool requested = (enabled == JNI_TRUE) && !golden_ultra_low_profile.load();
    const size_t requested_capacity = golden_ultra_low_profile.load()
            ? GOLDEN_ULTRA_LOW_RING_BUFFER_SIZE
            : (requested ? HIGH_BUFFER_RING_BUFFER_SIZE
               : (medium_buffer_profile.load() ? MEDIUM_BUFFER_RING_BUFFER_SIZE
                  : (low_buffer_profile.load() ? LOW_BUFFER_RING_BUFFER_SIZE : RING_BUFFER_SIZE)));

    {
        std::lock_guard<std::mutex> lock(ring_buffer_mutex);
        const size_t old_capacity = active_ring_buffer_capacity;
        const size_t old_used = ring_used;
        if (high_buffer_profile.load() == requested && old_capacity == requested_capacity) {
            // Still refresh the socket receive queue in case Android recreated it.
        } else {
            // Compact the existing PCM in chronological order when resizing. Enabling High
            // preserves all queued audio; leaving High keeps the newest data that fits the
            // selected non-High capacity (the other profiles retain their original policy).
            if (ring_buffer && old_used > 0 && old_capacity > 0) {
                const size_t keep = std::min(old_used, requested_capacity);
                const size_t skip = old_used - keep;
                std::vector<int16_t> preserved(keep);
                const size_t start = (ring_read_pos + skip) % old_capacity;
                const size_t first = std::min(keep, old_capacity - start);
                memcpy(preserved.data(), ring_buffer + start, first * sizeof(int16_t));
                if (first < keep) {
                    memcpy(preserved.data() + first, ring_buffer, (keep - first) * sizeof(int16_t));
                }
                memcpy(ring_buffer, preserved.data(), keep * sizeof(int16_t));
                ring_read_pos = 0;
                ring_used = keep;
                ring_write_pos = keep % requested_capacity;
            } else {
                ring_read_pos = ring_write_pos = ring_used = 0;
            }
            active_ring_buffer_capacity = requested_capacity;
            if (requested) {
                medium_buffer_profile.store(false);
                low_buffer_profile.store(false);
                custom_buffer_profile.store(false);
            }
            high_buffer_profile.store(requested);
        }
    }

    // High allows a larger kernel UDP receive queue so the native receive thread can
    // wait for ring capacity without immediately overflowing the socket during short stalls.
    if (tcp_socket >= 0 && !golden_ultra_low_profile.load()) {
        const int requestedSocketBytes = requested ? (2 * 1024 * 1024) :
                (medium_buffer_profile.load() ? (512 * 1024) :
                 (low_buffer_profile.load() ? (256 * 1024) : original_socket_receive_buffer_bytes));
        setsockopt(tcp_socket, SOL_SOCKET, SO_RCVBUF,
                   &requestedSocketBytes, sizeof(requestedSocketBytes));
        int actualSocketBytes = 0;
        socklen_t actualLength = sizeof(actualSocketBytes);
        getsockopt(tcp_socket, SOL_SOCKET, SO_RCVBUF,
                   &actualSocketBytes, &actualLength);
        LOGI("High continuity profile=%s; native ring=%zu samples; UDP SO_RCVBUF=%d bytes (requested=%d)",
             requested ? "ON" : "OFF", requested_capacity,
             actualSocketBytes, requestedSocketBytes);
    } else if (tcp_socket >= 0) {
        LOGI("High continuity profile=%s; Golden active, leaving its original UDP receive queue untouched",
             requested ? "ON" : "OFF");
    } else {
        LOGI("High continuity profile=%s; native ring=%zu samples (socket not connected yet)",
             requested ? "ON" : "OFF", requested_capacity);
    }

    ring_not_full.notify_all();
    ring_not_empty.notify_all();
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_wifiaudiospeaker_AudioStreamingService_nativeServiceSetMediumBufferProfile(
        JNIEnv *env, jclass clazz, jboolean enabled) {
    const bool requested = (enabled == JNI_TRUE) &&
            !golden_ultra_low_profile.load() && !high_buffer_profile.load();
    const size_t requested_capacity = golden_ultra_low_profile.load()
            ? GOLDEN_ULTRA_LOW_RING_BUFFER_SIZE
            : (high_buffer_profile.load() ? HIGH_BUFFER_RING_BUFFER_SIZE
               : (requested ? MEDIUM_BUFFER_RING_BUFFER_SIZE
                  : (low_buffer_profile.load() ? LOW_BUFFER_RING_BUFFER_SIZE : RING_BUFFER_SIZE)));

    {
        std::lock_guard<std::mutex> lock(ring_buffer_mutex);
        const size_t old_capacity = active_ring_buffer_capacity;
        const size_t old_used = ring_used;
        if (medium_buffer_profile.load() == requested && old_capacity == requested_capacity) {
            // Still refresh the moderate socket receive queue below.
        } else {
            // Keep the newest chronological PCM that fits when entering/leaving
            // Medium; never alter the Golden or High active capacity decisions.
            if (ring_buffer && old_used > 0 && old_capacity > 0) {
                const size_t keep = std::min(old_used, requested_capacity);
                const size_t skip = old_used - keep;
                std::vector<int16_t> preserved(keep);
                const size_t start = (ring_read_pos + skip) % old_capacity;
                const size_t first = std::min(keep, old_capacity - start);
                memcpy(preserved.data(), ring_buffer + start, first * sizeof(int16_t));
                if (first < keep) {
                    memcpy(preserved.data() + first, ring_buffer, (keep - first) * sizeof(int16_t));
                }
                memcpy(ring_buffer, preserved.data(), keep * sizeof(int16_t));
                ring_read_pos = 0;
                ring_used = keep;
                ring_write_pos = keep % requested_capacity;
            } else {
                ring_read_pos = ring_write_pos = ring_used = 0;
            }
            active_ring_buffer_capacity = requested_capacity;
            if (requested) {
                low_buffer_profile.store(false);
                custom_buffer_profile.store(false);
            }
            medium_buffer_profile.store(requested);
        }
    }

    // Moderate receive headroom: enough to absorb short Wi-Fi bursts without
    // inheriting High's multi-megabyte queue or Ultra Low's special settings.
    const int requestedSocketBytes = requested ? (512 * 1024) :
            (high_buffer_profile.load() ? (2 * 1024 * 1024) :
             (low_buffer_profile.load() ? (256 * 1024) : original_socket_receive_buffer_bytes));
    if (tcp_socket >= 0 && !golden_ultra_low_profile.load()) {
        setsockopt(tcp_socket, SOL_SOCKET, SO_RCVBUF,
                   &requestedSocketBytes, sizeof(requestedSocketBytes));
        int actualSocketBytes = 0;
        socklen_t actualLength = sizeof(actualSocketBytes);
        getsockopt(tcp_socket, SOL_SOCKET, SO_RCVBUF,
                   &actualSocketBytes, &actualLength);
        LOGI("Medium balanced profile=%s; native ring=%zu samples; UDP SO_RCVBUF=%d bytes (requested=%d)",
             requested ? "ON" : "OFF", requested_capacity,
             actualSocketBytes, requestedSocketBytes);
    } else if (tcp_socket >= 0) {
        LOGI("Medium balanced profile=%s; Golden active, leaving its original UDP receive queue untouched",
             requested ? "ON" : "OFF");
    } else {
        LOGI("Medium balanced profile=%s; native ring=%zu samples (socket not connected yet)",
             requested ? "ON" : "OFF", requested_capacity);
    }
    ring_not_full.notify_all();
    ring_not_empty.notify_all();
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_wifiaudiospeaker_AudioStreamingService_nativeServiceSetLowBufferProfile(
        JNIEnv *env, jclass clazz, jboolean enabled) {
    const bool requested = (enabled == JNI_TRUE) &&
            !golden_ultra_low_profile.load() && !high_buffer_profile.load();
    const size_t requested_capacity = golden_ultra_low_profile.load()
            ? GOLDEN_ULTRA_LOW_RING_BUFFER_SIZE
            : (high_buffer_profile.load() ? HIGH_BUFFER_RING_BUFFER_SIZE
               : (requested ? LOW_BUFFER_RING_BUFFER_SIZE
                  : (medium_buffer_profile.load() ? MEDIUM_BUFFER_RING_BUFFER_SIZE : RING_BUFFER_SIZE)));

    {
        std::lock_guard<std::mutex> lock(ring_buffer_mutex);
        const size_t old_capacity = active_ring_buffer_capacity;
        const size_t old_used = ring_used;
        if (low_buffer_profile.load() != requested || old_capacity != requested_capacity) {
            // Keep the newest chronological data that fits. Low has a smaller
            // ring than Medium, so a transition can discard only the oldest excess.
            if (ring_buffer && old_used > 0 && old_capacity > 0) {
                const size_t keep = std::min(old_used, requested_capacity);
                const size_t skip = old_used - keep;
                std::vector<int16_t> preserved(keep);
                const size_t start = (ring_read_pos + skip) % old_capacity;
                const size_t first = std::min(keep, old_capacity - start);
                memcpy(preserved.data(), ring_buffer + start, first * sizeof(int16_t));
                if (first < keep) {
                    memcpy(preserved.data() + first, ring_buffer, (keep - first) * sizeof(int16_t));
                }
                memcpy(ring_buffer, preserved.data(), keep * sizeof(int16_t));
                ring_read_pos = 0;
                ring_used = keep;
                ring_write_pos = keep % requested_capacity;
            } else {
                ring_read_pos = ring_write_pos = ring_used = 0;
            }
            active_ring_buffer_capacity = requested_capacity;
            if (requested) {
                medium_buffer_profile.store(false);
                custom_buffer_profile.store(false);
            }
            low_buffer_profile.store(requested);
        }
    }

    // Low uses moderate kernel receive headroom: enough for short Wi-Fi bursts,
    // substantially smaller than Medium and High. Golden always retains defaults.
    const int requestedSocketBytes = requested ? (256 * 1024) :
            (high_buffer_profile.load() ? (2 * 1024 * 1024) :
             (medium_buffer_profile.load() ? (512 * 1024) : original_socket_receive_buffer_bytes));
    if (tcp_socket >= 0 && !golden_ultra_low_profile.load()) {
        setsockopt(tcp_socket, SOL_SOCKET, SO_RCVBUF,
                   &requestedSocketBytes, sizeof(requestedSocketBytes));
        int actualSocketBytes = 0;
        socklen_t actualLength = sizeof(actualSocketBytes);
        getsockopt(tcp_socket, SOL_SOCKET, SO_RCVBUF,
                   &actualSocketBytes, &actualLength);
        LOGI("Low lip-sync profile=%s; native ring=%zu samples; UDP SO_RCVBUF=%d bytes (requested=%d)",
             requested ? "ON" : "OFF", requested_capacity,
             actualSocketBytes, requestedSocketBytes);
    } else if (tcp_socket >= 0) {
        LOGI("Low lip-sync profile=%s; Golden active, leaving its original UDP receive queue untouched",
             requested ? "ON" : "OFF");
    } else {
        LOGI("Low lip-sync profile=%s; native ring=%zu samples (socket not connected yet)",
             requested ? "ON" : "OFF", requested_capacity);
    }
    ring_not_full.notify_all();
    ring_not_empty.notify_all();
    return JNI_TRUE;
}


JNIEXPORT jboolean JNICALL
Java_com_example_wifiaudiospeaker_AudioStreamingService_nativeServiceSetCustomBufferProfile(
        JNIEnv *env, jclass clazz, jboolean enabled, jint targetMs) {
    const int safeTargetMs = std::max(10, std::min(2000, static_cast<int>(targetMs)));
    custom_buffer_target_ms.store(safeTargetMs);
    const bool requested = (enabled == JNI_TRUE) &&
            !golden_ultra_low_profile.load() && !high_buffer_profile.load() &&
            !medium_buffer_profile.load() && !low_buffer_profile.load();
    const size_t requested_capacity = golden_ultra_low_profile.load()
            ? GOLDEN_ULTRA_LOW_RING_BUFFER_SIZE
            : (high_buffer_profile.load() ? HIGH_BUFFER_RING_BUFFER_SIZE
               : (medium_buffer_profile.load() ? MEDIUM_BUFFER_RING_BUFFER_SIZE
                  : (low_buffer_profile.load() ? LOW_BUFFER_RING_BUFFER_SIZE
                     : (requested ? custom_ring_capacity_for_target_ms(safeTargetMs) : RING_BUFFER_SIZE))));

    {
        std::lock_guard<std::mutex> lock(ring_buffer_mutex);
        const size_t old_capacity = active_ring_buffer_capacity;
        const size_t old_used = ring_used;
        const bool old_profile = custom_buffer_profile.load();
        if (old_profile != requested || old_capacity != requested_capacity) {
            if (ring_buffer && old_used > 0 && old_capacity > 0) {
                const size_t keep = std::min(old_used, requested_capacity);
                const size_t skip = old_used - keep;
                std::vector<int16_t> preserved(keep);
                const size_t start = (ring_read_pos + skip) % old_capacity;
                const size_t first = std::min(keep, old_capacity - start);
                memcpy(preserved.data(), ring_buffer + start, first * sizeof(int16_t));
                if (first < keep) {
                    memcpy(preserved.data() + first, ring_buffer, (keep - first) * sizeof(int16_t));
                }
                memcpy(ring_buffer, preserved.data(), keep * sizeof(int16_t));
                ring_read_pos = 0;
                ring_used = keep;
                ring_write_pos = keep % requested_capacity;
            } else {
                ring_read_pos = ring_write_pos = ring_used = 0;
            }
            active_ring_buffer_capacity = requested_capacity;
        }
        if (requested) {
            golden_ultra_low_profile.store(false);
            high_buffer_profile.store(false);
            medium_buffer_profile.store(false);
            low_buffer_profile.store(false);
        }
        custom_buffer_profile.store(requested);
    }

    if (tcp_socket >= 0 && !golden_ultra_low_profile.load()) {
        const int requestedSocketBytes = requested ? (256 * 1024) :
                (high_buffer_profile.load() ? (2 * 1024 * 1024) :
                 (medium_buffer_profile.load() ? (512 * 1024) :
                  (low_buffer_profile.load() ? (256 * 1024) : original_socket_receive_buffer_bytes)));
        setsockopt(tcp_socket, SOL_SOCKET, SO_RCVBUF,
                   &requestedSocketBytes, sizeof(requestedSocketBytes));
        int actualSocketBytes = 0;
        socklen_t actualLength = sizeof(actualSocketBytes);
        getsockopt(tcp_socket, SOL_SOCKET, SO_RCVBUF,
                   &actualSocketBytes, &actualLength);
        LOGI("Custom adaptive profile=%s; target=%dms; native ring=%zu samples (~%zu ms); UDP SO_RCVBUF=%d bytes (requested=%d)",
             requested ? "ON" : "OFF", safeTargetMs, requested_capacity,
             requested_capacity * 1000U / static_cast<size_t>(SAMPLE_RATE),
             actualSocketBytes, requestedSocketBytes);
    } else {
        LOGI("Custom adaptive profile=%s; target=%dms; native ring=%zu samples (socket not connected or Golden owns socket)",
             requested ? "ON" : "OFF", safeTargetMs, requested_capacity);
    }
    ring_not_full.notify_all();
    ring_not_empty.notify_all();
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_com_example_wifiaudiospeaker_AudioStreamingService_nativeServiceGetCustomBufferRingDrops(
        JNIEnv *env, jclass clazz) {
    return static_cast<jlong>(custom_buffer_ring_drops.load());
}

JNIEXPORT jboolean JNICALL
Java_com_example_wifiaudiospeaker_AudioStreamingService_nativeServiceGetBufferStats(
        JNIEnv *env, jclass clazz, jintArray stats) {
    jint *statsArray = env->GetIntArrayElements(stats, NULL);
    if (statsArray == NULL) {
        return JNI_FALSE;
    }

    statsArray[0] = packets_received.load();
    statsArray[1] = packets_dropped.load();
    statsArray[2] = buffer_underruns.load();
    {
        // ring_used is mutex-protected. Reading it here makes the Java
        // fast-forward decision reflect packets that just arrived.
        std::lock_guard<std::mutex> lock(ring_buffer_mutex);
        statsArray[3] = static_cast<jint>(ring_used);
    }

    env->ReleaseIntArrayElements(stats, statsArray, 0);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_wifiaudiospeaker_MainActivity_nativeStartStreaming(JNIEnv *env, jclass clazz) {

    LOGI("Starting native streaming");
    g_force_disconnected.store(false);
    if (tcp_socket < 0) {
        LOGE("Invalid socket for streaming");
        return JNI_FALSE;
    }

    if (streaming_active.load()) {
        LOGI("Streaming already active");
        return JNI_TRUE;
    }

    // High mode uses back-pressure instead of evicting old PCM. Enlarge the
    // kernel UDP receive queue after the route/socket exists and before the
    // receiver thread begins. Golden's socket settings remain unchanged.
    if (high_buffer_profile.load() && !golden_ultra_low_profile.load()) {
        const int requestedSocketBytes = 2 * 1024 * 1024;
        if (setsockopt(tcp_socket, SOL_SOCKET, SO_RCVBUF,
                       &requestedSocketBytes, sizeof(requestedSocketBytes)) == 0) {
            int actualSocketBytes = 0;
            socklen_t actualLength = sizeof(actualSocketBytes);
            getsockopt(tcp_socket, SOL_SOCKET, SO_RCVBUF,
                       &actualSocketBytes, &actualLength);
            LOGI("High continuity UDP receive queue active: %d bytes (requested %d)",
                 actualSocketBytes, requestedSocketBytes);
        } else {
            LOGE("Could not enlarge High-mode UDP receive queue: %s", strerror(errno));
        }
    } else if (medium_buffer_profile.load() && !golden_ultra_low_profile.load()) {
        const int requestedSocketBytes = 512 * 1024;
        if (setsockopt(tcp_socket, SOL_SOCKET, SO_RCVBUF,
                       &requestedSocketBytes, sizeof(requestedSocketBytes)) == 0) {
            int actualSocketBytes = 0;
            socklen_t actualLength = sizeof(actualSocketBytes);
            getsockopt(tcp_socket, SOL_SOCKET, SO_RCVBUF,
                       &actualSocketBytes, &actualLength);
            LOGI("Medium balanced UDP receive queue active: %d bytes (requested %d)",
                 actualSocketBytes, requestedSocketBytes);
        } else {
            LOGE("Could not configure Medium UDP receive queue: %s", strerror(errno));
        }
    } else if (low_buffer_profile.load() && !golden_ultra_low_profile.load()) {
        const int requestedSocketBytes = 256 * 1024;
        if (setsockopt(tcp_socket, SOL_SOCKET, SO_RCVBUF,
                       &requestedSocketBytes, sizeof(requestedSocketBytes)) == 0) {
            int actualSocketBytes = 0;
            socklen_t actualLength = sizeof(actualSocketBytes);
            getsockopt(tcp_socket, SOL_SOCKET, SO_RCVBUF,
                       &actualSocketBytes, &actualLength);
            LOGI("Low lip-sync UDP receive queue active: %d bytes (requested %d)",
                 actualSocketBytes, requestedSocketBytes);
        } else {
            LOGE("Could not configure Low UDP receive queue: %s", strerror(errno));
        }
    }

    streaming_active.store(true);
    connection_health.store(100);

    packet_loss_count.store(0);
    successful_packets.store(0);
    packets_received.store(0);
    packets_dropped.store(0);
    buffer_underruns.store(0);
    custom_buffer_ring_drops.store(0);

    /* *** Insert await_preroll_samples before main streaming thread starts ***
    await_preroll_samples(480);  // Wait for 480 samples (≈ 10 ms at 48kHz) to be buffered

    // Reset ring buffer
    ring_write_pos = 0;
    ring_read_pos = 0;
    ring_used = 0;  */

    //The Commented  code above  waits for audio before the receiver thread has started receiving it.
    {
        std::lock_guard<std::mutex> lk(ring_buffer_mutex);
        ring_write_pos = 0;
        ring_read_pos = 0;
        ring_used = 0;
    }

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_JOINABLE);

    int result = pthread_create(&streaming_thread, &attr, streaming_thread_func, nullptr);
    pthread_attr_destroy(&attr);

    if (result != 0) {
        LOGE("Failed to create streaming thread: %s", strerror(result));
        streaming_active.store(false);
        return JNI_FALSE;
    }

    LOGI("Native streaming receiver started Successfully");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_wifiaudiospeaker_AudioStreamingService_nativeServiceWasForceDisconnected(
        JNIEnv *env, jclass clazz) {
    return g_force_disconnected.exchange(false) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_example_wifiaudiospeaker_MainActivity_nativeGetAudioData(JNIEnv *env, jclass clazz,
                                                                  jshortArray buffer,
                                                                  jint maxSamples) {
    if (!streaming_active.load() || tcp_socket < 0) {
        return 0;
    }

    jshort *native_buffer = env->GetShortArrayElements(buffer, nullptr);
    if (!native_buffer) {
        LOGE("Failed to get native buffer");
        return 0;
    }

    // Read from ring buffer instead of direct socket read ,with format validation
    size_t samples_read = read_from_ring_buffer((int16_t*)native_buffer, (size_t)maxSamples);

    // Log format every 10000 calls (~100s). Runtime format is otherwise stable;
    // verbose per-1000-call dumps used to crowd out connection diagnostics.
    static int call_count = 0;
    call_count++;
    if (call_count % 10000 == 0) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "NATIVE FORMAT CHECK - Call %d:", call_count);
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, " - Requested: %d samples", maxSamples);
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, " - Read: %zu samples (%zu bytes)", samples_read, samples_read * BYTES_PER_SAMPLE);
        size_t logged_used = 0;
        size_t logged_capacity = 0;
        {
            std::lock_guard<std::mutex> lock(ring_buffer_mutex);
            logged_used = ring_used;
            logged_capacity = active_ring_buffer_capacity;
        }
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, " - Ring buffer used: %zu/%zu samples", logged_used, logged_capacity);
        // Log first few sample values for format validation
        if (samples_read >= 4) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, " - Sample values: [%d,%d,%d,%d...]",
                 native_buffer[0], native_buffer[1], native_buffer[2], native_buffer[3] );
        }
    }


    env->ReleaseShortArrayElements(buffer, native_buffer, 0);

    if (samples_read > 0) {
        connection_health.store(std::min(100, connection_health.load() + 1));
        successful_packets.fetch_add(1);
    } else {
        // In High continuity mode this is a 200ms bounded wait for real PCM,
        // not necessarily an audible AudioTrack underrun: its 1500ms hardware
        // queue may still be playing. Do not count the wait as a dropped block.
        if (!high_buffer_profile.load() || golden_ultra_low_profile.load()) {
            buffer_underruns++;
            int current_health = connection_health.load();
            connection_health.store(std::max(0, current_health - 1));
        }
    }

    return (jint)samples_read;
}

JNIEXPORT jint JNICALL
Java_com_example_wifiaudiospeaker_MainActivity_nativeGetOptimalBufferSize(JNIEnv* env, jclass clazz,
                                                                          jint sampleRate, jint channels) {
    int base_size = (sampleRate * 8) / 1000; // 8ms worth of samples
    int backend_type = server_backend_type.load();
    int buffer_multiplier = adaptive_buffer_level.load();
    int optimal_size;

    switch (backend_type) {
        case 1: optimal_size = base_size * buffer_multiplier; break;
        case 2: optimal_size = base_size * (buffer_multiplier + 1); break;
        case 3: optimal_size = base_size * (buffer_multiplier + 2); break;
        default: optimal_size = base_size * (buffer_multiplier + 1); break;
    }

    // Round up to next power of 2
    int power_of_2 = 1;
    while (power_of_2 < optimal_size) {
        power_of_2 <<= 1;
    }

    power_of_2 = std::max(256, std::min(4096, power_of_2));

    LOGI("Optimal buffer size: %d samples (%.2fms at %dHz, backend_type=%d, level=%d)",
         power_of_2, (float)power_of_2 * 1000.0f / sampleRate, sampleRate, backend_type, buffer_multiplier);

    return power_of_2;
}

JNIEXPORT jint JNICALL
Java_com_example_wifiaudiospeaker_MainActivity_nativeGetConnectionHealth(JNIEnv* env, jclass clazz) {
    return connection_health.load();
}

JNIEXPORT jboolean JNICALL
Java_com_example_wifiaudiospeaker_MainActivity_nativeValidateConnection(JNIEnv* env, jclass clazz) {
    if (tcp_socket < 0) {
        LOGE("Invalid socket for validation");
        return JNI_FALSE;
    }

    int error = 0;
    socklen_t len = sizeof(error);
    int result = getsockopt(tcp_socket, SOL_SOCKET, SO_ERROR, &error, &len);

    if (result == 0 && error == 0) {
        LOGI("Connection validated successfully");
        return JNI_TRUE;
    } else {
        LOGE("Connection validation failed: %s", strerror(error));
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_example_wifiaudiospeaker_MainActivity_nativeOptimizeSocket(JNIEnv* env, jclass clazz) {
    if (tcp_socket < 0) {
        LOGE("Invalid socket for optimization");
        return JNI_FALSE;
    }
    
    int buffer_size = multi_backend_server.load() ? 131072 : 98304;
    setsockopt(tcp_socket, SOL_SOCKET, SO_RCVBUF, &buffer_size, sizeof(buffer_size));
    setsockopt(tcp_socket, SOL_SOCKET, SO_SNDBUF, &buffer_size, sizeof(buffer_size));
    
    LOGI("Socket optimization complete (buffer_size=%d)", buffer_size);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_wifiaudiospeaker_MainActivity_nativeSetStreamingParameters(JNIEnv* env, jclass clazz,
                                                                            jint bufferSize, jint sampleRate, jint channels) {
    LOGI("Setting streaming parameters: buffer=%d, rate=%d, channels=%d", bufferSize, sampleRate, channels);
    current_buffer_size = bufferSize;

    if (bufferSize <= 512) {
        adaptive_buffer_level.store(1);
    } else if (bufferSize <= 1024) {
        adaptive_buffer_level.store(2);
    } else if (bufferSize <= 2048) {
        adaptive_buffer_level.store(3);
    } else {
        adaptive_buffer_level.store(3);
    }

    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_example_wifiaudiospeaker_MainActivity_nativeReadWithRetry(JNIEnv *env, jclass clazz,
                                                                   jbyteArray buffer, jint maxBytes) {
    if (tcp_socket < 0) {
        return -1;
    }

    jbyte *native_buffer = env->GetByteArrayElements(buffer, nullptr);
    if (!native_buffer) {
        return -1;
    }

    ssize_t total_read = 0;
    int attempts = 0;
    const int max_attempts = 10;

    while (total_read < maxBytes && attempts < max_attempts) {
        ssize_t bytes_read = recv(tcp_socket, native_buffer + total_read,
                                  maxBytes - total_read, MSG_DONTWAIT);

        if (bytes_read > 0) {
            total_read += bytes_read;
            if (total_read >= maxBytes) {
                break;
            }
        } else if (bytes_read == 0) {
            LOGI("Connection closed during read");
            break;
        } else {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                attempts++;
                usleep(attempts <= 5 ? 300 : 600);
            } else {
                LOGE("Socket read error: %s", strerror(errno));
                break;
            }
        }
    }

    env->ReleaseByteArrayElements(buffer, native_buffer, 0);

    if (total_read > 0) {
        LOGI("Read %zd bytes in %d attempts", total_read, attempts + 1);
        successful_packets.fetch_add(1);
    } else {
        packet_loss_count.fetch_add(1);
    }

    return (jint)total_read;
}


JNIEXPORT void JNICALL
Java_com_example_wifiaudiospeaker_AudioStreamingService_nativeServiceFlushAudioBuffer(
        JNIEnv *env, jclass clazz) {
    std::lock_guard<std::mutex> lock(ring_buffer_mutex);

    if (!ring_buffer) {
        return;
    }

    ring_read_pos = 0;
    ring_write_pos = 0;
    ring_used = 0;

    ring_not_full.notify_all();
    ring_not_empty.notify_all();
}

static bool join_streaming_thread_locked() {
    if (streaming_thread == 0) {
        return true;
    }

    if (pthread_equal(pthread_self(), streaming_thread)) {
        LOGE("Refusing to pthread_join the streaming thread from itself");
        return false;
    }

    void *thread_result = nullptr;
    const int join_result = pthread_join(streaming_thread, &thread_result);

    // Clear the handle even on a failed join so a second teardown path cannot
    // attempt to join the same thread again.
    streaming_thread = 0;

    if (join_result != 0) {
        LOGE("Failed to join streaming thread: %s", strerror(join_result));
        return false;
    }

    return true;
}

JNIEXPORT void JNICALL
Java_com_example_wifiaudiospeaker_MainActivity_nativeStopStreaming(JNIEnv *env, jclass clazz) {
    std::lock_guard<std::mutex> lifecycle_lock(native_lifecycle_mutex);

    LOGI("Stopping native streaming");

    streaming_active.store(false);
    ring_not_empty.notify_all();
    ring_not_full.notify_all();

    join_streaming_thread_locked();

    connection_health.store(0);
    server_backend_type.store(0);
    multi_backend_server.store(false);
    packet_loss_count.store(0);
    successful_packets.store(0);
    adaptive_buffer_level.store(2);

    LOGI("Native streaming stopped");
}

JNIEXPORT void JNICALL
Java_com_example_wifiaudiospeaker_MainActivity_nativeCleanup(JNIEnv *env, jclass clazz) {
    std::lock_guard<std::mutex> lifecycle_lock(native_lifecycle_mutex);
    LOGI("Cleaning up native resources");

    streaming_active.store(false);

    ring_not_empty.notify_all();
    ring_not_full.notify_all();

    join_streaming_thread_locked();

    if (tcp_socket >= 0) {
        close(tcp_socket);
        tcp_socket = -1;
    }
    original_socket_receive_buffer_bytes = 0;

    cleanup_ring_buffer();

    if (stream_buffer) {
        delete[] stream_buffer;
        stream_buffer = nullptr;
        buffer_size = 0;
    }

    g_inited.store(false);
    {
        std::lock_guard<std::mutex> lock(g_preferred_local_ip_mutex);
        g_preferred_local_ip.clear();
    }

    connection_health.store(0);
    server_backend_type.store(0);
    multi_backend_server.store(false);
    packet_loss_count.store(0);
    successful_packets.store(0);
    adaptive_buffer_level.store(2);

    LOGI("Native cleanup complete");
}

JNIEXPORT jboolean JNICALL
Java_com_example_wifiaudiospeaker_MainActivity_nativeSetJitterBuffer(
        JNIEnv *env, jclass clazz, jint bufferMs) {
    return Java_com_example_wifiaudiospeaker_AudioStreamingService_nativeServiceSetJitterBuffer(env, clazz, bufferMs);
}

// Service wrapper functions
JNIEXPORT jboolean JNICALL
Java_com_example_wifiaudiospeaker_AudioStreamingService_nativeServiceInit(JNIEnv* env, jclass clazz) {
    return Java_com_example_wifiaudiospeaker_MainActivity_nativeInit(env, clazz);
}

JNIEXPORT jboolean JNICALL
Java_com_example_wifiaudiospeaker_AudioStreamingService_nativeServiceConnect(JNIEnv* env, jclass clazz, jstring ip, jint port) {
    return Java_com_example_wifiaudiospeaker_MainActivity_nativeConnect(env, clazz, ip, port);
}

JNIEXPORT jboolean JNICALL
Java_com_example_wifiaudiospeaker_AudioStreamingService_nativeServiceStartStreaming(JNIEnv* env, jclass clazz) {
    return Java_com_example_wifiaudiospeaker_MainActivity_nativeStartStreaming(env, clazz);
}

JNIEXPORT jint JNICALL
Java_com_example_wifiaudiospeaker_AudioStreamingService_nativeServiceGetAudioData(JNIEnv* env, jclass clazz, jshortArray buffer, jint maxSamples) {
    return Java_com_example_wifiaudiospeaker_MainActivity_nativeGetAudioData(env, clazz, buffer, maxSamples);
}

JNIEXPORT void JNICALL
Java_com_example_wifiaudiospeaker_AudioStreamingService_nativeServiceStopStreaming(JNIEnv* env, jclass clazz) {
    Java_com_example_wifiaudiospeaker_MainActivity_nativeStopStreaming(env, clazz);
}

JNIEXPORT void JNICALL
Java_com_example_wifiaudiospeaker_AudioStreamingService_nativeServiceCleanup(JNIEnv* env, jclass clazz) {
    Java_com_example_wifiaudiospeaker_MainActivity_nativeCleanup(env, clazz);
}

JNIEXPORT jint JNICALL
Java_com_example_wifiaudiospeaker_AudioStreamingService_nativeServiceGetOptimalBufferSize(JNIEnv* env, jclass clazz, jint sampleRate, jint channels) {
    return Java_com_example_wifiaudiospeaker_MainActivity_nativeGetOptimalBufferSize(env, clazz, sampleRate, channels);
}

} // extern "C"
