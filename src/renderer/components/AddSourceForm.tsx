import { useState } from 'react';

type SourceType = 'm3u_url' | 'm3u_file' | 'xtream';

interface AddSourceFormProps {
  onSourceAdded: () => void;
}

export function AddSourceForm({ onSourceAdded }: AddSourceFormProps) {
  const [isOpen, setIsOpen] = useState(false);
  const [sourceType, setSourceType] = useState<SourceType>('m3u_url');
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [filePath, setFilePath] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  const resetForm = () => {
    setName('');
    setUrl('');
    setFilePath('');
    setUsername('');
    setPassword('');
    setError('');
    setSuccess('');
  };

  const handleBrowseFile = async () => {
    const path = await window.api.dialog.openM3uFile();
    if (path) {
      setFilePath(path);
      if (!name) {
        const fileName = path.split(/[/\\]/).pop()?.replace(/\.[^.]+$/, '') ?? '';
        setName(fileName);
      }
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setSuccess('');
    setLoading(true);

    try {
      const input = {
        name,
        type: sourceType,
        ...(sourceType === 'm3u_url' && { url }),
        ...(sourceType === 'm3u_file' && { filePath }),
        ...(sourceType === 'xtream' && { url, username, password }),
      };

      const result = await window.api.sources.add(input);

      if (result.ok) {
        const msg = result.syncedCount
          ? `Source added! ${result.syncedCount} entries imported.`
          : 'Source added!';
        setSuccess(result.syncError ? `${msg} (Sync warning: ${result.syncError})` : msg);
        resetForm();
        onSourceAdded();
      } else {
        setError(result.error || 'Failed to add source');
      }
    } catch (err) {
      setError(String(err));
    } finally {
      setLoading(false);
    }
  };

  if (!isOpen) {
    return (
      <button
        onClick={() => setIsOpen(true)}
        className="flex w-full items-center justify-center gap-2 rounded-xl border border-dashed border-surface-600 bg-surface-900/50 py-4 text-sm font-medium text-surface-400 transition-colors hover:border-accent hover:text-accent"
      >
        <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
          <path strokeLinecap="round" strokeLinejoin="round" d="M12 4v16m8-8H4" />
        </svg>
        Add Source
      </button>
    );
  }

  return (
    <section className="rounded-xl border border-surface-800 bg-surface-900 p-5">
      <div className="mb-4 flex items-center justify-between">
        <h3 className="text-lg font-semibold text-surface-200">Add Source</h3>
        <button
          onClick={() => {
            setIsOpen(false);
            resetForm();
          }}
          className="text-surface-400 hover:text-surface-200"
        >
          <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      {/* Source type tabs */}
      <div className="mb-4 flex gap-1 rounded-lg bg-surface-800 p-1">
        {([
          ['m3u_url', 'M3U URL'],
          ['m3u_file', 'M3U File'],
          ['xtream', 'Xtream'],
        ] as const).map(([type, label]) => (
          <button
            key={type}
            onClick={() => {
              setSourceType(type);
              setError('');
            }}
            className={`flex-1 rounded-md px-3 py-1.5 text-sm font-medium transition-colors ${
              sourceType === type
                ? 'bg-accent text-white'
                : 'text-surface-400 hover:text-surface-200'
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      <form onSubmit={handleSubmit} className="space-y-3">
        <Input label="Name" value={name} onChange={setName} placeholder="My IPTV Source" required />

        {sourceType === 'm3u_url' && (
          <Input label="URL" value={url} onChange={setUrl} placeholder="https://example.com/playlist.m3u" required />
        )}

        {sourceType === 'm3u_file' && (
          <div>
            <label className="mb-1 block text-sm font-medium text-surface-300">File</label>
            <div className="flex gap-2">
              <input
                type="text"
                value={filePath}
                readOnly
                placeholder="Select an M3U file..."
                className="flex-1 rounded-lg border border-surface-700 bg-surface-800 px-3 py-2 text-sm text-surface-200 placeholder-surface-500"
              />
              <button
                type="button"
                onClick={handleBrowseFile}
                className="rounded-lg bg-surface-700 px-4 py-2 text-sm font-medium text-surface-200 hover:bg-surface-600"
              >
                Browse
              </button>
            </div>
          </div>
        )}

        {sourceType === 'xtream' && (
          <>
            <Input label="Server URL" value={url} onChange={setUrl} placeholder="http://provider.example.com:8080" required />
            <Input label="Username" value={username} onChange={setUsername} required />
            <Input label="Password" value={password} onChange={setPassword} type="password" required />
          </>
        )}

        {error && (
          <p className="rounded-lg bg-red-500/10 px-3 py-2 text-sm text-red-400">{error}</p>
        )}
        {success && (
          <p className="rounded-lg bg-green-500/10 px-3 py-2 text-sm text-green-400">{success}</p>
        )}

        <button
          type="submit"
          disabled={loading}
          className="w-full rounded-lg bg-accent px-4 py-2.5 text-sm font-medium text-white transition-colors hover:bg-accent-hover disabled:opacity-50"
        >
          {loading ? 'Adding & Syncing...' : 'Add Source'}
        </button>
      </form>
    </section>
  );
}

function Input({
  label,
  value,
  onChange,
  placeholder,
  required,
  type = 'text',
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  required?: boolean;
  type?: string;
}) {
  return (
    <div>
      <label className="mb-1 block text-sm font-medium text-surface-300">{label}</label>
      <input
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        required={required}
        className="w-full rounded-lg border border-surface-700 bg-surface-800 px-3 py-2 text-sm text-surface-200 placeholder-surface-500 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
      />
    </div>
  );
}
