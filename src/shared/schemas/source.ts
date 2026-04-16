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
