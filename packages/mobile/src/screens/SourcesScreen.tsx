import React, { useState } from 'react';
import {
  View,
  Text,
  TextInput,
  ScrollView,
  StatusBar,
  StyleSheet,
} from 'react-native';
import { TvButton } from '../components/tv/TvButton';
import { useNavStore } from '../stores/nav-store';
import { useSourcesStore } from '../stores/sources-store';
import { colors } from '../styles/theme';

type Tab = 'm3u' | 'xtream' | 'stalker';

const MAC_RE = /^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$/;

export function SourcesScreen() {
  const navigate = useNavStore((s) => s.navigate);
  const sources = useSourcesStore((s) => s.sources);
  const syncStatus = useSourcesStore((s) => s.syncStatus);
  const syncMessage = useSourcesStore((s) => s.syncMessage);
  const addM3uSource = useSourcesStore((s) => s.addM3uSource);
  const addXtreamSource = useSourcesStore((s) => s.addXtreamSource);
  const addStalkerSource = useSourcesStore((s) => s.addStalkerSource);
  const removeSource = useSourcesStore((s) => s.removeSource);
  const resync = useSourcesStore((s) => s.resync);

  const [tab, setTab] = useState<Tab>('m3u');
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

  const canAdd =
    tab === 'm3u' ? canAddM3u : tab === 'xtream' ? canAddXtream : canAddStalker;

  return (
    <ScrollView
      style={styles.root}
      contentContainerStyle={styles.content}
    >
      <StatusBar barStyle="light-content" backgroundColor={colors.surface900} />

      <View style={styles.headerRow}>
        <Text style={styles.headerTitle}>Sources</Text>
        <TvButton label="Back" onSelect={() => navigate('home')} />
      </View>

      <View style={styles.card}>
        <View style={styles.tabs}>
          <TvButton label="M3U" onSelect={() => setTab('m3u')} active={tab === 'm3u'} autoFocus />
          <TvButton
            label="Xtream"
            onSelect={() => setTab('xtream')}
            active={tab === 'xtream'}
          />
          <TvButton
            label="Stalker"
            onSelect={() => setTab('stalker')}
            active={tab === 'stalker'}
          />
        </View>

        <Text style={styles.inputLabel}>Name</Text>
        <TextInput
          value={name}
          onChangeText={setName}
          placeholder="My provider"
          placeholderTextColor={colors.surface500}
          style={styles.input}
          autoCapitalize="none"
          autoCorrect={false}
        />

        <Text style={styles.inputLabel}>
          {tab === 'm3u' ? 'M3U URL' : tab === 'xtream' ? 'Server URL' : 'Portal URL'}
        </Text>
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

        {tab === 'xtream' && (
          <>
            <Text style={styles.inputLabel}>Username</Text>
            <TextInput
              value={username}
              onChangeText={setUsername}
              placeholder="username"
              placeholderTextColor={colors.surface500}
              style={styles.input}
              autoCapitalize="none"
              autoCorrect={false}
            />

            <Text style={styles.inputLabel}>Password</Text>
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
          </>
        )}

        {tab === 'stalker' && (
          <>
            <Text style={styles.inputLabel}>MAC Address</Text>
            <TextInput
              value={macAddress}
              onChangeText={setMacAddress}
              placeholder="00:1A:79:XX:XX:XX"
              placeholderTextColor={colors.surface500}
              style={styles.input}
              autoCapitalize="characters"
              autoCorrect={false}
            />
          </>
        )}

        <View style={styles.addRow}>
          <TvButton label="Add & Sync" onSelect={handleAdd} active={canAdd} />
        </View>

        {syncStatus !== 'idle' && (
          <Text
            style={[
              styles.syncMsg,
              syncStatus === 'error' ? styles.syncMsgError : styles.syncMsgNormal,
            ]}
          >
            {syncMessage}
          </Text>
        )}
      </View>

      <Text style={styles.listHeader}>
        Sources ({sources.length})
      </Text>

      {sources.length === 0 ? (
        <Text style={styles.empty}>No sources yet. Add one above.</Text>
      ) : (
        sources.map((src) => (
          <View key={src.id} style={styles.sourceRow}>
            <View style={styles.sourceInfo}>
              <View style={styles.sourceTitleRow}>
                <Text style={styles.sourceName}>{src.name}</Text>
                <Text style={styles.sourceBadge}>
                  {src.type === 'm3u_url'
                    ? 'M3U'
                    : src.type === 'xtream'
                      ? 'Xtream'
                      : 'Stalker'}
                </Text>
              </View>
              <Text style={styles.sourceUrl} numberOfLines={1}>
                {src.url}
              </Text>
              <Text style={styles.sourceMeta}>
                {src.channelCount} items
                {src.lastSynced
                  ? ` • synced ${new Date(src.lastSynced).toLocaleTimeString()}`
                  : ''}
              </Text>
              {src.lastError && (
                <Text style={styles.sourceError}>{src.lastError}</Text>
              )}
            </View>
            <View style={styles.sourceActions}>
              <TvButton label="Resync" onSelect={() => void resync(src.id)} />
              <TvButton label="Remove" onSelect={() => void removeSource(src.id)} />
            </View>
          </View>
        ))
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: colors.surface900,
  },
  content: {
    padding: 48,
  },
  headerRow: {
    marginBottom: 32,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  headerTitle: {
    fontSize: 36,
    fontWeight: '800',
    color: colors.white,
  },
  card: {
    marginBottom: 32,
    borderRadius: 16,
    backgroundColor: colors.surface800,
    padding: 24,
  },
  tabs: {
    marginBottom: 16,
    flexDirection: 'row',
    gap: 12,
  },
  inputLabel: {
    marginBottom: 8,
    fontSize: 14,
    color: colors.surface500,
  },
  input: {
    marginBottom: 16,
    borderRadius: 12,
    backgroundColor: colors.surface700,
    paddingHorizontal: 16,
    paddingVertical: 12,
    color: colors.white,
    fontSize: 16,
  },
  addRow: {
    flexDirection: 'row',
    gap: 12,
  },
  syncMsg: {
    marginTop: 16,
    fontSize: 14,
  },
  syncMsgError: {
    color: colors.red400,
  },
  syncMsgNormal: {
    color: colors.surface400,
  },
  listHeader: {
    marginBottom: 16,
    fontSize: 20,
    fontWeight: '600',
    color: colors.white,
  },
  empty: {
    color: colors.surface500,
  },
  sourceRow: {
    marginBottom: 12,
    borderRadius: 12,
    backgroundColor: colors.surface800,
    padding: 16,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  sourceInfo: {
    flex: 1,
  },
  sourceTitleRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  sourceName: {
    fontSize: 16,
    fontWeight: '600',
    color: colors.white,
  },
  sourceBadge: {
    marginLeft: 8,
    borderRadius: 6,
    backgroundColor: colors.surface600,
    paddingHorizontal: 8,
    paddingVertical: 2,
    fontSize: 12,
    color: colors.surface400,
    overflow: 'hidden',
  },
  sourceUrl: {
    marginTop: 4,
    fontSize: 12,
    color: colors.surface500,
  },
  sourceMeta: {
    marginTop: 4,
    fontSize: 12,
    color: colors.surface400,
  },
  sourceError: {
    marginTop: 4,
    fontSize: 12,
    color: colors.red400,
  },
  sourceActions: {
    flexDirection: 'row',
    gap: 8,
  },
});
