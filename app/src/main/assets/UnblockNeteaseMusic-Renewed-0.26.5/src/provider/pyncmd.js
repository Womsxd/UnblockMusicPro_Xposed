const cache = require('../cache');
const select = require('./select');
const request = require('../request');

const track = (info) => {
	const url =
		'https://service-ghlrryee-1308098780.gz.apigw.tencentcs.com/release/pyncmd/track/GetTrackAudio?song_ids=' +
		info.id +
		'&bitrate=' + ['999000', '320000'].slice(
			select.ENABLE_FLAC ? 0 : 1,
			select.ENABLE_FLAC ? 1 : 2
		);
	//console.log(headers); //debug
	return request('GET', url)
		.then((response) => response.body())
		.then((body) => {
			// response.body() without raw should
			// transform the response to string.
			if (typeof body !== 'string')
				return Promise.reject(
					'response.body() returns a value whose type is not string.'
				);
			//console.log(body); //debug
			const jsonBody = JSON.parse(body);
			const matched = jsonBody.data.find((song) => song.id === info.id);
			if (matched) return {
				url: matched.url,
				weight: Number.MAX_VALUE
			};
			return Promise.reject();
		});
};

const check = (info) => cache(track, info);
get_cookie()

module.exports = {
	check
};
