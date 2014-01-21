package com.github.kumaraman21.intellijbehave.utility;


import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author <a href="http://twitter.com/aloyer">@aloyer</a>
 */
public class ParametrizedString {

	private static Pattern compileParameterPattern(final String parameterPrefix) {
		return Pattern.compile("(\\" + parameterPrefix + "\\w*)(\\W|\\Z)", Pattern.DOTALL);
	}

	private final List<Token> tokens = new ArrayList<Token>();
	private final String content;
	private final String parameterPrefix;


	public ParametrizedString(final String content) {
		this(content, "$");
	}

	public ParametrizedString(final String content, final String parameterPrefix) {
		if (content == null) {
			throw new IllegalArgumentException("Content cannot be null");
		}
		this.content = content;
		this.parameterPrefix = parameterPrefix;
		parse(compileParameterPattern(parameterPrefix));
	}

	@Override
	public int hashCode() {
		return content.hashCode();
	}

	@Override
	public boolean equals(final Object obj) {
		return (obj instanceof ParametrizedString) && isSameAs((ParametrizedString) obj);
	}

	public boolean isSameAs(final ParametrizedString other) {
		return other.content.equals(content);
	}

	private void parse(final Pattern parameterPattern) {
		final Matcher matcher = parameterPattern.matcher(content);

		int prev = 0;
		while (matcher.find()) {
			int start = matcher.start();
			int end = matcher.end();
			if (start > prev) {
				add(new Token(prev, start - prev, false));
			}
			end -= matcher.group(2).length();
			start += parameterPrefix.length(); // remove prefix from the identifier
			add(new Token(start, end - start, true));
			prev = end;
		}
		if (prev < content.length()) {
			add(new Token(prev, content.length() - prev, false));
		}
	}

	private void add(final Token token) {
		tokens.add(token);
	}

	public class Token {
		private final int offset;
		private final int length;
		private final boolean isIdentifier;

		public Token(final int offset, final int length, final boolean isIdentifier) {
			this.offset = offset;
			this.length = length;
			this.isIdentifier = isIdentifier;
		}

		public String value() {
			return content.substring(getOffset(), getOffset() + getLength());
		}

		@Override
		public String toString() {
			return "<<" + (isIdentifier() ? "$" : "") + value() + ">>";
		}

		public boolean regionMatches(final int toffset, final String other, final int ooffset, final int len) {
			try {
				return normalize(content, getOffset() + toffset, len)
						.equalsIgnoreCase(normalize(other, ooffset, len));
			} catch (final java.lang.StringIndexOutOfBoundsException e) {
				return false;
			}
		}

		private String normalize(final String input, final int offset, final int len) {
			return input.substring(offset, offset + len)
					.replaceAll("\\s+", "");
		}

		public int getOffset() {
			return offset;
		}

		public int getLength() {
			return length;
		}

		public boolean isIdentifier() {
			return isIdentifier;
		}
	}

	public Token getToken(final int index) {
		return tokens.get(index);
	}

	public int getTokenCount() {
		return tokens.size();
	}

	public WeightChain calculateWeightChain(final String input) {
		final WeightChain chain = acceptsBeginning(0, input, 0);
		chain.input = input;
		chain.collectWeights();
		return chain;
	}

	public static class StringToken {
		private final String value;
		private final boolean identifier;

		public StringToken(final String value, final boolean identifier) {
			this.value = value;
			this.identifier = identifier;
		}

		public String getValue() {
			return value;
		}

		public boolean isIdentifier() {
			return identifier;
		}
	}

	public List<StringToken> tokenize(final String stepInput) {

		final List<StringToken> stringTokens = new ArrayList<StringToken>();

		WeightChain chain = calculateWeightChain(stepInput);
		final List<String> inputTokens = chain.tokenize();
		while (chain != null) {
			if (!chain.isZero()) {
				final Token token = tokens.get(chain.getTokenIndex());
				final String value = inputTokens.get(chain.getTokenIndex());
				stringTokens.add(new StringToken(value, token.isIdentifier()));
			}
			chain = chain.getNext();
		}
		return stringTokens;
	}

	private WeightChain acceptsBeginning(int inputIndex, final String input, final int tokenIndexStart) {
		final WeightChain pair = new WeightChain();
		pair.inputIndex = inputIndex;

		WeightChain current = pair;

		final List<Token> tokenList = this.tokens;
		for (int tokenIndex = tokenIndexStart, n = tokenList.size(); tokenIndex < n; tokenIndex++) {
			final boolean isLastToken = (tokenIndex == n - 1);
			final Token token = tokenList.get(tokenIndex);
			if (!token.isIdentifier()) {
				final int remaining = input.length() - inputIndex;
				if (remaining > token.getLength() && isLastToken) {
					// more data than the token itself
					return WeightChain.zero();
				}

				final int overlaping = Math.min(token.getLength(), remaining);
				if (overlaping > 0) {
					if (token.regionMatches(0, input, inputIndex, overlaping)) {
						current.tokenIndex = tokenIndex;
						current.weight++;
						if (overlaping == token.getLength()) // full token match
						{
							current.weight++;
							if ((inputIndex + overlaping) == input.length())
							// no more data, break the loop now
							{
								return pair;
							}
						} // break looop
						else {
							return pair;
						}

						inputIndex += overlaping;
						final WeightChain next = new WeightChain();
						next.inputIndex = inputIndex;
						current.next = next;
						current = next;
					} else {
						// no match
						return WeightChain.zero();
					}
				} else {
					// not enough data, returns what has been collected
					return pair;
				}
			} else {
				current.tokenIndex = tokenIndex;
				current.weight++;

				// not the most efficient part, but no other solution right now
				WeightChain next = WeightChain.zero();
				for (int j = inputIndex + 1; j < input.length(); j++) {
					final WeightChain sub = acceptsBeginning(j, input, tokenIndex + 1);
					if (sub.hasMoreWeightThan(next)) {
						next = sub;
					}
				}
				current.next = next;
				return pair;
			}
		}
		return pair;
	}

	public static class WeightChain {
		public static WeightChain zero() {
			return new WeightChain();
		}

		private String input;
		private int inputIndex;
		private int weight;
		private int tokenIndex = -1;
		private WeightChain next;

		public WeightChain last() {
			WeightChain last = this;
			WeightChain iter = this;
			while (iter != null) {
				if (!iter.isZero()) {
					last = iter;
				}
				iter = iter.next;
			}
			return last;
		}

		public boolean isZero() {
			return weight == 0 && tokenIndex == -1;
		}

		public WeightChain getNext() {
			return next;
		}

		public int getTokenIndex() {
			return tokenIndex;
		}

		public boolean hasMoreWeightThan(final WeightChain pair) {
			if (weight > pair.weight) {
				return true;
			}
			return false;
		}

		@Override
		public String toString() {
			return "WeightChain [inputIndex=" + inputIndex + ", weight=" + weight + ", tokenIndex=" + tokenIndex + "]";
		}

		public void collectWeights() {
			int w = weight;
			WeightChain n = next;
			while (n != null) {
				if (!n.isZero()) {
					w += n.weight;
				}
				n = n.next;
			}

			this.weight = w;
		}

		public List<String> tokenize() {
			final List<String> parts = new ArrayList<String>();
			if (isZero()) {
				return parts;
			}

			int indexBeg = inputIndex;
			WeightChain n = next;
			while (n != null) {
				if (!n.isZero()) {
					parts.add(input.substring(indexBeg, n.inputIndex));
					indexBeg = n.inputIndex;
				}
				n = n.next;
			}
			parts.add(input.substring(indexBeg));

			return parts;
		}
	}

	public String complete(final String input) {
		final WeightChain chain = calculateWeightChain(input);
		final WeightChain last = chain.last();
		if (last.isZero()) {
			return "";
		}
		final int inputIndex = last.inputIndex;
		int tokenIndex = last.tokenIndex;

		final StringBuilder builder = new StringBuilder();

		Token token = getToken(tokenIndex);
		if (!token.isIdentifier()) {
			final int consumed = input.length() - inputIndex;
			builder.append(getToken(tokenIndex).value().substring(consumed));
		}
		tokenIndex++;
		for (int i = tokenIndex; i < getTokenCount(); i++) {
			token = getToken(i);
			if (token.isIdentifier()) {
				builder.append(parameterPrefix);
			}
			builder.append(token.value());
		}
		return builder.toString();
	}

}
