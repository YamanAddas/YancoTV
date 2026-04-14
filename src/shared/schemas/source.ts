import { z } from 'zod';

export const sourceTypeSchema = z.enum(['m3u_url', 'm3u_file', 'xtream']);

export const addSourceInputSchema = z
  .object({
    name: z.string().min(1, 'Name is required').max(100),
    type: sourceTypeSchema,
    url: z.string().url().optional(),
    filePath: z.string().optional(),
    username: z.string().optional(),
    password: z.string().optional(),
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
  });

export type AddSourceInput = z.infer<typeof addSourceInputSchema>;
