package dev.peekapi;

import static org.junit.jupiter.api.Assertions.*;

import dev.peekapi.internal.SsrfProtection;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SsrfProtectionTest {

  // --- isLocalhost ---

  @Test
  void localhostIsAllowed() {
    assertTrue(SsrfProtection.isLocalhost("localhost"));
    assertTrue(SsrfProtection.isLocalhost("127.0.0.1"));
    assertTrue(SsrfProtection.isLocalhost("::1"));
    assertTrue(SsrfProtection.isLocalhost("LOCALHOST"));
  }

  @Test
  void nonLocalhostIsNotLocalhost() {
    assertFalse(SsrfProtection.isLocalhost("example.com"));
    assertFalse(SsrfProtection.isLocalhost("10.0.0.1"));
    assertFalse(SsrfProtection.isLocalhost(null));
  }

  // --- isPrivateAddress IPv4 ---

  @ParameterizedTest
  @ValueSource(
      strings = {
        "0.0.0.0",
        "0.255.255.255",
        "10.0.0.1",
        "10.255.255.255",
        "100.64.0.1",
        "100.127.255.255",
        "127.0.0.1",
        "127.255.255.255",
        "169.254.0.1",
        "169.254.255.255",
        "172.16.0.1",
        "172.31.255.255",
        "192.168.0.1",
        "192.168.255.255",
      })
  void privateIpv4IsDetected(String ip) throws UnknownHostException {
    assertTrue(SsrfProtection.isPrivateAddress(InetAddress.getByName(ip)), ip);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "1.1.1.1",
        "8.8.8.8",
        "100.128.0.1",
        "172.32.0.1",
        "192.169.0.1",
        "203.0.113.1",
      })
  void publicIpv4IsAllowed(String ip) throws UnknownHostException {
    assertFalse(SsrfProtection.isPrivateAddress(InetAddress.getByName(ip)), ip);
  }

  // --- isPrivateAddress IPv6 ---

  @Test
  void ipv6LoopbackIsPrivate() throws UnknownHostException {
    assertTrue(SsrfProtection.isPrivateAddress(InetAddress.getByName("::1")));
  }

  @Test
  void ipv6UniqueLocalIsPrivate() throws UnknownHostException {
    assertTrue(SsrfProtection.isPrivateAddress(InetAddress.getByName("fc00::1")));
    assertTrue(SsrfProtection.isPrivateAddress(InetAddress.getByName("fd00::1")));
  }

  @Test
  void ipv6LinkLocalIsPrivate() throws UnknownHostException {
    assertTrue(SsrfProtection.isPrivateAddress(InetAddress.getByName("fe80::1")));
  }

  @Test
  void ipv4MappedIpv6IsPrivate() throws UnknownHostException {
    // ::ffff:10.0.0.1
    assertTrue(SsrfProtection.isPrivateAddress(InetAddress.getByName("::ffff:10.0.0.1")));
    // ::ffff:192.168.1.1
    assertTrue(SsrfProtection.isPrivateAddress(InetAddress.getByName("::ffff:192.168.1.1")));
  }

  @Test
  void ipv4MappedIpv6PublicIsAllowed() throws UnknownHostException {
    assertFalse(SsrfProtection.isPrivateAddress(InetAddress.getByName("::ffff:8.8.8.8")));
  }

  // --- validateHost ---

  @Test
  void validateHostAllowsLocalhost() {
    assertDoesNotThrow(() -> SsrfProtection.validateHost("localhost"));
    assertDoesNotThrow(() -> SsrfProtection.validateHost("127.0.0.1"));
  }

  @Test
  void validateHostRejectsEmptyHost() {
    assertThrows(IllegalArgumentException.class, () -> SsrfProtection.validateHost(""));
    assertThrows(IllegalArgumentException.class, () -> SsrfProtection.validateHost(null));
  }

  @Test
  void validateHostAllowsPublicDomain() {
    assertDoesNotThrow(() -> SsrfProtection.validateHost("ingest.peekapi.dev"));
  }

  @Test
  void validateHostRejectsPrivateIp() {
    assertThrows(IllegalArgumentException.class, () -> SsrfProtection.validateHost("10.0.0.1"));
    assertThrows(IllegalArgumentException.class, () -> SsrfProtection.validateHost("192.168.1.1"));
    assertThrows(IllegalArgumentException.class, () -> SsrfProtection.validateHost("172.16.0.1"));
  }
}
