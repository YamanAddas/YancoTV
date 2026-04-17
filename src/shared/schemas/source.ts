import { z } from 'zod';

export const sourceTypeSchema = z.enum(['m3u_url', 'm3u_file', 'xtream', 'stalker']);

const MAC_REGEX = /^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$/;

export const addSourceInputSchema = z
  .object({
    name: z.string().min(1, 'Name is required').max(100),
    type: sourceTypeSchema,
    url: z.string().url().optional(),
    filePath: z.string().optional(),
    username: z.string().optional(),
    password: z.string().optional(),
    macAddress: z.string().optional(),
    epgUrl: z.string().url().optional().or(z.literal('')),
  })
  .superRefine((data, ctx) => {
    if (data.type === 'm3u_url' && !data.url) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: 'URL is required for M3U URL sources',
        path: ['url'],
      });
    }
    if (data.type === 'm3u_file' && !data.filePath) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: 'File path is required for M3U file sources',
        path: ['filePath'],
      });
    }
    if (data.type === 'xtream') {
      if (!data.url) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: 'Server URL is required for Xtream sources',
          path: ['url'],
        });
      }
      if (!data.username) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: 'Username is required for Xtream sources',
          path: ['username'],
        });
      }
      if (!data.password) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: 'Password is required for Xtream sources',
          path: ['password'],
        });
      }
    }
    if (data.type === 'stalker') {
      if (!data.url) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: 'Portal URL is required for Stalker sources',
          path: ['url'],
        });
      }
      if (!data.macAddress) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: 'MAC address is required for Stalker sources',
          path: ['macAddress'],
        });
      } else if (!MAC_REGEX.test(data.macAddress)) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: 'MAC address must be in format XX:XX:XX:XX:XX:XX',
          path: ['macAddress'],
        });
      }
    }
  });

export type AddSourceInput = z.infer<typeof addSourceInputSchema>;

export const updateSourceInputSchema = z.object({
  id: z.string().min(1, 'Source ID is required'),
  name: z.string().min(1).max(100).optional(),
  url: z.string().url().optional(),
  username: z.string().optional(),
  password: z.string().optional(),
  macAddress: z
    .string()
    .regex(MAC_REGEX, 'MAC address must be in format XX:XX:XX:XX:XX:XX')
    .optional(),
  epgUrl: z.string().url().optional().or(z.literal('')),
  autoSyncInterval: z.number().int().min(0).max(720).optional(),
});

export type UpdateSourceInput = z.infer<typeof updateSourceInputSchema>;

// ---------------------------------------------------------------------------
// IPC input schemas (general — not source-specific but kept here so all
// boundary validation lives in one folder for now).
// ---------------------------------------------------------------------------

export const reorderIdsSchema = z.array(z.string().min(1)).max(1000);

export const groupPrefSetSchema = z.object({
  contentType: z.string().min(1).max(50),
  groupKey: z.string().min(1).max(500),
  sortOrder: z.number().int().min(0).max(100000).optional(),
  isHidden: z.boolean().optional(),
  isPinned: z.boolean().optional(),
  customName: z.string().max(200).nullable().optional(),
});
export type GroupPrefSetInput = z.infer<typeof groupPrefSetSchema>;

export const groupPrefReorderSchema = z.object({
  contentType: z.string().min(1).max(50),
  orderedKeys: z.array(z.string().min(1)).max(1000),
});

export const channelOverrideSchema = z.object({
  contentId: z.string().min(1).max(200),
  customName: z.string().max(200).optional(),
  customLogoUrl: z.string().url().max(2000).optional().or(z.literal('')),
  customNumber: z.number().int().min(0).max(100000).optional(),
  customGroup: z.string().max(200).optional(),
});
export type ChannelOverrideInput = z.infer<typeof channelOverrideSchema>;
