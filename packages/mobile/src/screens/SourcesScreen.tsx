import React, { useState } from 'react';
import {
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { PageHeader } from '../components/layout/PageHeader';
import { useSourcesStore } from '../stores/sources-store';
import { colors, radii, spacing } from '../styles/theme';

type Tab = 'm3u' | 'xtream' | 'stalker';

const MAC_RE = /^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$/;

export function SourcesScreen() {
  const sources = useSourcesStore((s) => s.sources);
  const syncStatus = useSourcesStore((s) => s.syncStatus);
  const syncMessage = useSourcesStore((s) => s.syncMessage);
  const addM3uSource = useSourcesStore((s) => s.addM3uSource);
  const addXtreamSource = useSourcesStore((s) => s.addXtreamSource);
  const addStalkerSource = useSourcesStore((s) => s.addStalkerSource);
  const removeSource = useSourcesStore((s) => s.removeSource);
  const resync = useSourcesStore((s) => s.resync);

  const [tab, setTab] = useState<Tab>('xtream');
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [macAddress, setMacAddress] = useState('');

  const urlOk = url.trim().startsWith('http');
  const canAddM3u = name.trim().length > 0 && urlOk;
  const canAddXtream =
    name.trim().length > 0 &&
    urlOk &&
    username.trim().length > 0 &&
    password.trim().length > 0;
  const canAddStalker =
    name.trim().length > 0 && urlOk && MAC_RE.test(macAddress.trim());

  const resetForm = () => {
    setName('');
    setUrl('');
    setUsername('');
    setPassword('');
    setMacAddress('');
  };

  const handleAdd = async () => {
    if (tab === 'm3u') {
      if (!canAddM3u) return;
      const input = { name: name.trim(), url: url.trim() };
      resetForm();
      await addM3uSource(input);
    } else if (tab === 'xtream') {
      if (!canAddXtream) return;
      const input = {
        name: name.trim(),
        url: url.trim(),
        username: username.trim(),
        password: password.trim(),
      };
      resetForm();
      await addXtreamSource(input);
    } else {
      if (!canAddStalker) return;
      const input = {
        name: name.trim(),
        url: url.trim(),
        macAddress: macAddress.trim().toUpperCase(),
      };
      resetForm();
      await addStalkerSource(input);
    }
  };

  // MB-3: block submit while a sync is in flight, otherwise the user can fire
  // a duplicate add of the same source and watch the channels list double up.
  const isSyncing = syncStatus === 'fetching' || syncStatus === 'parsing';
  const canAdd =
    !isSyncing &&
    (tab === 'm3u' ? canAddM3u : tab === 'xtream' ? canAddXtream : canAddStalker);

  return (
    <ScrollView contentContainerStyle={styles.content}>
      <PageHeader
        eyebrow="Playlists"
        title="Sources"
        subtitle="Connect your IPTV provider"
      />

      <View style={styles.card}>
        <View style={styles.tabs}>
          {(['m3u', 'xtream', 'stalker'] as Tab[]).map((t) => (
            <Pressable
              key={t}
              onPress={() => setTab(t)}
              style={({ focused }) => [
                styles.tabBtn,
                tab === t && styles.tabBtnActive,
                focused && tab !== t && styles.tabBtnFocus,
              ]}
            >
              <Text
                style={[styles.tabText, tab === t && styles.tabTextActive]}
              >
                {t === 'm3u' ? 'M3U' : t === 'xtream' ? 'Xtream' : 'Stalker'}
              </Text>
            </Pressable>
          ))}
        </View>

        <Field label="Name">
          <TextInput
            value={name}
            onChangeText={setName}
            placeholder="My provider"
            placeholderTextColor={colors.surface500}
            style={styles.input}
            autoCapitalize="none"
            autoCorrect={false}
          />
        </Field>

        <Field
          label={
            tab === 'm3u'
              ? 'M3U URL'
              : tab === 'xtream'
                ? 'Server URL'
                : 'Portal URL'
          }
        >
          <TextInput
            value={url}
            onChangeText={setUrl}
            placeholder={
              tab === 'm3u'
                ? 'http://example.com/playlist.m3u'
                : tab === 'xtream'
                  ? 'http://portal.example.com:8080'
                  : 'http://portal.example.com/c/'
            }
            placeholderTextColor={colors.surface500}
            style={styles.input}
            autoCapitalize="none"
            autoCorrect={false}
            keyboardType="url"
          />
        </Field>

        {tab === 'xtream' && (
          <>
            <Field label="Username">
              <TextInput
                value={username}
                onChangeText={setUsername}
                placeholder="username"
                placeholderTextColor={colors.surface500}
                style={styles.input}
                autoCapitalize="none"
                autoCorrect={false}
              />
            </Field>
            <Field label="Password">
              <TextInput
                value={password}
                onChangeText={setPassword}
                placeholder="password"
                placeholderTextColor={colors.surface500}
                style={styles.input}
                autoCapitalize="none"
                autoCorrect={false}
                secureTextEntry
              />
            </Field>
          </>
        )}

        {tab === 'stalker' && (
          <Field label="MAC Address">
            <TextInput
              value={macAddress}
              onChangeText={setMacAddress}
              placeholder="00:1A:79:XX:XX:XX"
              placeholderTextColor={colors.surface500}
              style={styles.input}
              autoCapitalize="characters"
              autoCorrect={false}
            />
          </Field>
        )}

        <Pressable
          onPress={handleAdd}
          disabled={!canAdd}
          style={({ pressed }) => [
            styles.primaryBtn,
            !canAdd && styles.primaryBtnDisabled,
            pressed && canAdd && { opacity: 0.85 },
          ]}
        >
          <Text
            style={[
              styles.primaryBtnText,
              !canAdd && styles.primaryBtnTextDisabled,
            ]}
          >
            Add & Sync
          </Text>
        </Pressable>

        {syncStatus !== 'idle' && syncMessage ? (
          <Text
            style={[
              styles.syncMsg,
              syncStatus === 'error' ? styles.syncMsgError : styles.syncMsgNormal,
            ]}
          >
            {syncStatus === 'fetching' ? '⟳ ' : syncStatus === 'error' ? '✕ ' : '✓ '}
            {syncMessage}
          </Text>
        ) : null}
      </View>

      <Text style={styles.listHeader}>
        Saved ({sources.length})
      </Text>

      {sources.length === 0 ? (
        <Text style={styles.empty}>No sources yet. Add one above.</Text>
      ) : (
        sources.map((src) => (
          <View key={src.id} style={styles.sourceRow}>
            <View style={styles.sourceInfo}>
              <View style={styles.sourceTitleRow}>
                <Text style={styles.sourceName}>{src.name}</Text>
                <View style={styles.sourceBadge}>
                  <Text style={styles.sourceBadgeText}>
                    {src.type === 'm3u_url'
                      ? 'M3U'
                      : src.type === 'xtream'
                        ? 'XTREAM'
                        : 'STALKER'}
                  </Text>
                </View>
              </View>
              <Text style={styles.sourceUrl} numberOfLines={1}>
                {src.url}
              </Text>
              <Text style={styles.sourceMeta}>
                {src.channelCount.toLocaleString()} items
                {src.lastSynced
                  ? ` · ${new Date(src.lastSynced).toLocaleTimeString()}`
                  : ''}
              </Text>
              {src.lastError && (
                <Text style={styles.sourceError}>{src.lastError}</Text>
              )}
            </View>
            <View style={styles.sourceActions}>
              <SmallBtn label="Resync" onPress={() => void resync(src.id)} />
              <SmallBtn
                label="Remove"
                danger
                onPress={() => void removeSource(src.id)}
              />
            </View>
          </View>
        ))
      )}
    </ScrollView>
  );
}

function Field({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <View style={{ marginBottom: 14 }}>
      <Text style={styles.fieldLabel}>{label}</Text>
      {children}
    </View>
  );
}

function SmallBtn({
  label,
  onPress,
  danger,
}: {
  label: string;
  onPress: () => void;
  danger?: boolean;
}) {
  return (
    <Pressable
      onPress={onPress}
      style={({ pressed, focused }) => [
        styles.smallBtn,
        danger && styles.smallBtnDanger,
        (pressed || focused) && styles.smallBtnFocus,
      ]}
    >
      <Text style={[styles.smallBtnText, danger && styles.smallBtnTextDanger]}>
        {label}
      </Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  content: {
    paddingBottom: spacing.xxl,
  },
  card: {
    marginHorizontal: spacing.xl,
    marginTop: spacing.md,
    padding: spacing.lg,
    borderRadius: radii.lg,
    backgroundColor: colors.surface900,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.05)',
  },
  tabs: {
    flexDirection: 'row',
    backgroundColor: colors.surface800,
    borderRadius: radii.md,
    padding: 4,
    marginBottom: spacing.md,
  },
  tabBtn: {
    flex: 1,
    paddingVertical: 10,
    alignItems: 'center',
    borderRadius: radii.sm,
  },
  tabBtnActive: {
    backgroundColor: colors.accent,
  },
  tabBtnFocus: {
    backgroundColor: 'rgba(255,255,255,0.05)',
  },
  tabText: {
    color: colors.surface300,
    fontSize: 13,
    fontWeight: '700',
    letterSpacing: 0.5,
  },
  tabTextActive: {
    color: colors.bg,
  },
  fieldLabel: {
    color: colors.surface400,
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 1,
    textTransform: 'uppercase',
    marginBottom: 6,
  },
  input: {
    backgroundColor: colors.surface800,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
    borderRadius: radii.md,
    paddingHorizontal: 14,
    paddingVertical: 12,
    color: colors.white,
    fontSize: 14,
  },
  primaryBtn: {
    marginTop: 4,
    backgroundColor: colors.accent,
    paddingVertical: 14,
    borderRadius: radii.md,
    alignItems: 'center',
  },
  primaryBtnDisabled: {
    backgroundColor: colors.surface700,
  },
  primaryBtnText: {
    color: colors.bg,
    fontSize: 14,
    fontWeight: '800',
    letterSpacing: 1,
  },
  primaryBtnTextDisabled: {
    color: colors.surface500,
  },
  syncMsg: {
    marginTop: spacing.md,
    fontSize: 13,
  },
  syncMsgError: {
    color: colors.red400,
  },
  syncMsgNormal: {
    color: colors.accent,
  },
  listHeader: {
    marginHorizontal: spacing.xl,
    marginTop: spacing.xl,
    marginBottom: spacing.sm,
    fontSize: 13,
    fontWeight: '700',
    color: colors.surface400,
    letterSpacing: 1,
    textTransform: 'uppercase',
  },
  empty: {
    marginHorizontal: spacing.xl,
    color: colors.surface500,
    fontSize: 13,
    paddingVertical: spacing.md,
  },
  sourceRow: {
    marginHorizontal: spacing.xl,
    marginBottom: spacing.sm + 4,
    borderRadius: radii.md,
    backgroundColor: colors.surface900,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.05)',
    padding: spacing.md,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.md,
  },
  sourceInfo: {
    flex: 1,
  },
  sourceTitleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  sourceName: {
    fontSize: 15,
    fontWeight: '700',
    color: colors.white,
  },
  sourceBadge: {
    backgroundColor: 'rgba(0, 255, 170, 0.12)',
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: radii.sm,
  },
  sourceBadgeText: {
    fontSize: 9,
    color: colors.accent,
    fontWeight: '800',
    letterSpacing: 1,
  },
  sourceUrl: {
    marginTop: 4,
    fontSize: 11,
    color: colors.surface500,
  },
  sourceMeta: {
    marginTop: 2,
    fontSize: 11,
    color: colors.surface400,
  },
  sourceError: {
    marginTop: 4,
    fontSize: 11,
    color: colors.red400,
  },
  sourceActions: {
    flexDirection: 'row',
    gap: 6,
  },
  smallBtn: {
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: radii.sm,
    backgroundColor: colors.surface800,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.06)',
  },
  smallBtnFocus: {
    borderColor: colors.accent,
  },
  smallBtnDanger: {
    backgroundColor: 'rgba(248, 113, 113, 0.08)',
    borderColor: 'rgba(248, 113, 113, 0.3)',
  },
  smallBtnText: {
    color: colors.surface200,
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 0.5,
  },
  smallBtnTextDanger: {
    color: colors.red400,
  },
});
