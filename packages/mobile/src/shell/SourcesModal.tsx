import React, { useState } from 'react';
import {
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { useShellStore } from '../stores/shell-store';
import { useSourcesStore, type MobileSource } from '../stores/sources-store';
import { colors, radii, spacing } from '../styles/theme';

// Minimal source-management surface. Rendered as an absolute overlay inside
// HomeShell (state-driven, rule 3 — not a route). Replaces the old
// SourcesScreen deleted in M4R.1 so we have SOMEthing to get content into
// the DB while SettingsModal + the proper M3U picker land in M7R.
//
// Two tabs: M3U URL | Xtream. Paste + submit. Existing sources list with
// Sync + Delete. Sync status line at the bottom.

type Tab = 'm3u' | 'xtream';

export function SourcesModal() {
  const open = useShellStore((s) => s.sourcesModalOpen);
  const close = useShellStore((s) => s.closeSourcesModal);

  if (!open) return null;
  return <ModalBody onClose={close} />;
}

function ModalBody({ onClose }: { onClose: () => void }) {
  const sources = useSourcesStore((s) => s.sources);
  const syncStatus = useSourcesStore((s) => s.syncStatus);
  const syncMessage = useSourcesStore((s) => s.syncMessage);
  const addM3u = useSourcesStore((s) => s.addM3uSource);
  const addXtream = useSourcesStore((s) => s.addXtreamSource);
  const resync = useSourcesStore((s) => s.resync);
  const removeSource = useSourcesStore((s) => s.removeSource);

  const [tab, setTab] = useState<Tab>('m3u');
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const submit = async () => {
    setFormError(null);
    if (!name.trim()) {
      setFormError('Name required');
      return;
    }
    if (!url.trim()) {
      setFormError('URL required');
      return;
    }
    setSubmitting(true);
    try {
      if (tab === 'm3u') {
        await addM3u({ name: name.trim(), url: url.trim() });
      } else {
        if (!username.trim() || !password.trim()) {
          setFormError('Username + password required for Xtream');
          setSubmitting(false);
          return;
        }
        await addXtream({
          name: name.trim(),
          url: url.trim(),
          username: username.trim(),
          password: password,
        });
      }
      setName('');
      setUrl('');
      setUsername('');
      setPassword('');
    } catch (e: unknown) {
      setFormError(e instanceof Error ? e.message : String(e));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <View style={styles.backdrop}>
      <View style={styles.panel}>
        <View style={styles.header}>
          <Text style={styles.title}>Sources</Text>
          <Pressable
            onPress={onClose}
            style={({ focused }) => [styles.closeBtn, focused && styles.btnFocused]}
          >
            {({ focused }) => (
              <Text style={[styles.closeLabel, focused && styles.labelFocused]}>
                Close
              </Text>
            )}
          </Pressable>
        </View>

        <ScrollView
          style={styles.body}
          contentContainerStyle={styles.bodyContent}
          keyboardShouldPersistTaps="handled"
        >
          <Text style={styles.sectionTitle}>Add a source</Text>
          <View style={styles.tabRow}>
            <TabButton label="M3U URL" active={tab === 'm3u'} onPress={() => setTab('m3u')} />
            <TabButton label="Xtream" active={tab === 'xtream'} onPress={() => setTab('xtream')} />
          </View>

          <Field label="Name" value={name} onChangeText={setName} placeholder="My provider" />
          <Field
            label={tab === 'm3u' ? 'M3U URL' : 'Xtream host'}
            value={url}
            onChangeText={setUrl}
            placeholder={
              tab === 'm3u'
                ? 'https://provider/get.php?...'
                : 'http://provider.example.com:8080'
            }
            autoCapitalize="none"
            keyboardType="url"
          />
          {tab === 'xtream' && (
            <>
              <Field
                label="Username"
                value={username}
                onChangeText={setUsername}
                autoCapitalize="none"
              />
              <Field
                label="Password"
                value={password}
                onChangeText={setPassword}
                secureTextEntry
                autoCapitalize="none"
              />
            </>
          )}

          {formError && <Text style={styles.errorText}>{formError}</Text>}

          <Pressable
            onPress={submit}
            disabled={submitting}
            style={({ focused }) => [
              styles.submitBtn,
              focused && styles.btnFocused,
              submitting && styles.submitBtnDisabled,
            ]}
          >
            {({ focused }) => (
              <Text style={[styles.submitLabel, focused && styles.labelFocused]}>
                {submitting ? 'Adding…' : 'Add + sync'}
              </Text>
            )}
          </Pressable>

          <View style={styles.sectionDivider} />

          <Text style={styles.sectionTitle}>Existing ({sources.length})</Text>
          {sources.length === 0 ? (
            <Text style={styles.emptyLine}>No sources yet.</Text>
          ) : (
            sources.map((src) => (
              <SourceRow
                key={src.id}
                source={src}
                onResync={() => resync(src.id)}
                onDelete={() => removeSource(src.id)}
              />
            ))
          )}
        </ScrollView>

        {syncStatus !== 'idle' && (
          <View style={styles.statusBar}>
            <Text style={styles.statusText} numberOfLines={2}>
              [{syncStatus}] {syncMessage ?? ''}
            </Text>
          </View>
        )}
      </View>
    </View>
  );
}

function TabButton({
  label,
  active,
  onPress,
}: {
  label: string;
  active: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable
      onPress={onPress}
      style={({ focused }) => [
        styles.tabBtn,
        active && styles.tabBtnActive,
        focused && styles.btnFocused,
      ]}
    >
      {({ focused }) => (
        <Text
          style={[
            styles.tabLabel,
            active && styles.tabLabelActive,
            focused && styles.labelFocused,
          ]}
        >
          {label}
        </Text>
      )}
    </Pressable>
  );
}

interface FieldProps {
  label: string;
  value: string;
  onChangeText: (v: string) => void;
  placeholder?: string;
  secureTextEntry?: boolean;
  autoCapitalize?: 'none' | 'sentences' | 'words' | 'characters';
  keyboardType?: 'default' | 'url' | 'email-address';
}

function Field({
  label,
  value,
  onChangeText,
  placeholder,
  secureTextEntry,
  autoCapitalize,
  keyboardType,
}: FieldProps) {
  return (
    <View style={styles.field}>
      <Text style={styles.fieldLabel}>{label}</Text>
      <TextInput
        value={value}
        onChangeText={onChangeText}
        placeholder={placeholder}
        placeholderTextColor={colors.surface500}
        secureTextEntry={secureTextEntry}
        autoCapitalize={autoCapitalize}
        keyboardType={keyboardType}
        style={styles.input}
        autoCorrect={false}
      />
    </View>
  );
}

function SourceRow({
  source,
  onResync,
  onDelete,
}: {
  source: MobileSource;
  onResync: () => void;
  onDelete: () => void;
}) {
  return (
    <View style={styles.sourceRow}>
      <View style={styles.sourceRowText}>
        <Text style={styles.sourceName} numberOfLines={1}>
          {source.name}
        </Text>
        <Text style={styles.sourceMeta} numberOfLines={1}>
          {source.type} · {source.channelCount} items
          {source.lastSynced
            ? ` · synced ${new Date(source.lastSynced).toLocaleTimeString()}`
            : ' · never synced'}
        </Text>
        {source.lastError && (
          <Text style={styles.sourceError} numberOfLines={2}>
            {source.lastError}
          </Text>
        )}
      </View>
      <Pressable
        onPress={onResync}
        style={({ focused }) => [styles.rowBtn, focused && styles.btnFocused]}
      >
        {({ focused }) => (
          <Text style={[styles.rowBtnLabel, focused && styles.labelFocused]}>
            Sync
          </Text>
        )}
      </Pressable>
      <Pressable
        onPress={onDelete}
        style={({ focused }) => [styles.rowBtn, focused && styles.btnFocused]}
      >
        {({ focused }) => (
          <Text style={[styles.rowBtnLabel, focused && styles.labelFocused]}>
            Delete
          </Text>
        )}
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: 'rgba(0, 0, 0, 0.7)',
    alignItems: 'center',
    justifyContent: 'center',
    padding: spacing.xl,
    zIndex: 200,
    elevation: 200,
  },
  panel: {
    width: '100%',
    maxWidth: 720,
    maxHeight: '90%',
    backgroundColor: colors.surface900,
    borderWidth: 1,
    borderColor: colors.glassBorder,
    borderRadius: radii.lg,
    overflow: 'hidden',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: spacing.lg,
    borderBottomWidth: 1,
    borderBottomColor: colors.glassBorderSoft,
  },
  title: {
    color: colors.white,
    fontSize: 22,
    fontWeight: '800',
  },
  closeBtn: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    borderWidth: 1,
    borderColor: colors.glassBorder,
    borderRadius: radii.sm,
  },
  closeLabel: {
    color: colors.surface200,
    fontSize: 14,
    fontWeight: '700',
  },
  body: {
    flexGrow: 0,
  },
  bodyContent: {
    padding: spacing.lg,
  },
  sectionTitle: {
    color: colors.accent,
    fontSize: 14,
    fontWeight: '800',
    letterSpacing: 1,
    textTransform: 'uppercase',
    marginBottom: spacing.md,
  },
  tabRow: {
    flexDirection: 'row',
    gap: spacing.sm,
    marginBottom: spacing.md,
  },
  tabBtn: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    borderRadius: radii.sm,
    borderWidth: 1,
    borderColor: colors.glassBorder,
  },
  tabBtnActive: {
    backgroundColor: colors.glass,
    borderColor: colors.accent,
  },
  tabLabel: {
    color: colors.surface200,
    fontSize: 14,
    fontWeight: '700',
  },
  tabLabelActive: {
    color: colors.white,
  },
  field: {
    marginBottom: spacing.md,
  },
  fieldLabel: {
    color: colors.surface300,
    fontSize: 12,
    fontWeight: '700',
    textTransform: 'uppercase',
    letterSpacing: 0.5,
    marginBottom: spacing.xs,
  },
  input: {
    backgroundColor: colors.surface800,
    borderWidth: 1,
    borderColor: colors.glassBorder,
    borderRadius: radii.sm,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    color: colors.white,
    fontSize: 14,
  },
  errorText: {
    color: colors.red400,
    fontSize: 13,
    marginBottom: spacing.md,
  },
  submitBtn: {
    backgroundColor: colors.accent,
    borderRadius: radii.sm,
    paddingVertical: spacing.md,
    alignItems: 'center',
    marginTop: spacing.sm,
    borderWidth: 2,
    borderColor: 'transparent',
  },
  submitBtnDisabled: {
    opacity: 0.6,
  },
  submitLabel: {
    color: '#000',
    fontSize: 15,
    fontWeight: '800',
    letterSpacing: 0.5,
  },
  sectionDivider: {
    height: 1,
    backgroundColor: colors.glassBorderSoft,
    marginVertical: spacing.xl,
  },
  emptyLine: {
    color: colors.surface400,
    fontSize: 13,
  },
  sourceRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    padding: spacing.md,
    backgroundColor: colors.surface800,
    borderRadius: radii.sm,
    marginBottom: spacing.sm,
  },
  sourceRowText: {
    flex: 1,
  },
  sourceName: {
    color: colors.white,
    fontSize: 14,
    fontWeight: '700',
  },
  sourceMeta: {
    color: colors.surface400,
    fontSize: 11,
    marginTop: 2,
  },
  sourceError: {
    color: colors.red400,
    fontSize: 11,
    marginTop: 2,
  },
  rowBtn: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    borderWidth: 1,
    borderColor: colors.glassBorder,
    borderRadius: radii.sm,
  },
  rowBtnLabel: {
    color: colors.surface200,
    fontSize: 12,
    fontWeight: '700',
  },
  btnFocused: {
    borderColor: colors.focus,
  },
  labelFocused: {
    color: colors.focus,
  },
  statusBar: {
    padding: spacing.md,
    borderTopWidth: 1,
    borderTopColor: colors.glassBorderSoft,
    backgroundColor: colors.surface800,
  },
  statusText: {
    color: colors.surface200,
    fontSize: 12,
    fontFamily: 'monospace',
  },
});
